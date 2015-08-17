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
namespace org.apache.zookeeper.recipes.@lock
{
	/// <summary>
	/// A <a href="package.html">protocol to implement an exclusive
	///  write lock or to elect a leader</a>. <p/> You invoke <seealso cref="Lock()"/> to 
	///  start the process of grabbing the lock; you may get the lock then or it may be 
	///  some time later. <p/> You can register a listener so that you are invoked 
	///  when you get the lock; otherwise you can ask if you have the lock
	///  by calling <seealso cref="Owner"/>
	/// 
	/// </summary>
	public sealed class WriteLock : ProtocolSupport
	{
		private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(WriteLock));

	    private readonly AsyncLock lockable = new AsyncLock();

		private readonly string dir;
	    private ZNodeName idName;
		private string ownerId;
		private string lastChildId;
		private readonly byte[] data = {0x12, 0x34};
		private LockListener callback;
		private readonly LockZooKeeperOperation zop;

		/// <summary>
		/// zookeeper contructor for writelock </summary>
		/// <param name="zookeeper"> zookeeper client instance </param>
		/// <param name="dir"> the parent path you want to use for locking </param>
		/// <param name="acls"> the acls that you want to use for all the paths, 
		/// if null world read/write is used. </param>
		public WriteLock(ZooKeeper zookeeper, string dir, List<ACL> acls) : base(zookeeper)
		{
			this.dir = dir;
			if (acls != null)
			{
				m_acl = acls;
			}
			zop = new LockZooKeeperOperation(this);
		}

		/// <summary>
		/// zookeeper contructor for writelock with callback </summary>
		/// <param name="zookeeper"> the zookeeper client instance </param>
		/// <param name="dir"> the parent path you want to use for locking </param>
		/// <param name="acl"> the acls that you want to use for all the paths </param>
		/// <param name="callback"> the call back instance </param>
		public WriteLock(ZooKeeper zookeeper, string dir, List<ACL> acl, LockListener callback) : this(zookeeper, dir, acl)
		{
			this.callback = callback;
		}

	    /// <summary>
	    /// return the current locklistener </summary>
	    /// <returns> the locklistener </returns>
	    public async Task setLockListener(LockListener lockListener) {
	        using(await lockable.LockAsync()) {
	            callback = lockListener;
	        }
	    }


	    /// <summary>
		/// Removes the lock or associated znode if 
		/// you no longer require the lock. this also 
		/// removes your request in the queue for locking
		/// in case you do not already hold the lock. </summary>
		public async Task unlock()
		{
			using(await lockable.LockAsync())
			{
				if (!Closed && Id != null)
				{
					// we don't need to retry this operation in the case of failure
					// as ZK will remove ephemeral files and we don't wanna hang
					// this process when closing if we cannot reconnect to ZK
					try
					{
						ZooKeeperOperation zopdel = new ZooKeeperOperationAnonymousInnerClassHelper(this);
						await zopdel.execute();
					}
					catch (KeeperException.NoNodeException)
					{
						// do nothing
					}
					catch (KeeperException e)
					{
						LOG.warn("Caught: " + e, e);
						throw;
					}
					finally
					{
						if (callback != null)
						{
							callback.lockReleased();
						}
						Id = null;
					}
				}
			}
		}

		private sealed class ZooKeeperOperationAnonymousInnerClassHelper : ZooKeeperOperation
		{
			private readonly WriteLock outerInstance;

			public ZooKeeperOperationAnonymousInnerClassHelper(WriteLock outerInstance)
			{
				this.outerInstance = outerInstance;
			}

			public async Task<bool> execute()
			{
				await outerInstance.zookeeper.deleteAsync(outerInstance.Id);
				return true;
			}
		}

		/// <summary>
		/// the watcher called on  
		/// getting watch while watching 
		/// my predecessor
		/// </summary>
		private class LockWatcher : Watcher
		{
			private readonly WriteLock outerInstance;

			public LockWatcher(WriteLock outerInstance)
			{
				this.outerInstance = outerInstance;
			}

			public override async Task process(WatchedEvent @event)
			{
				// lets either become the leader or watch the new/updated node
				LOG.debug("Watcher fired on path: " + @event.getPath() + " state: " + @event.getState() + " type " + @event.get_Type());
				try
				{
					await outerInstance.Lock();
				}
				catch (Exception e)
				{
					LOG.warn("Failed to acquire lock: " + e, e);
				}
			}
		}

		/// <summary>
		/// a zoookeeper operation that is mainly responsible
		/// for all the magic required for locking.
		/// </summary>
		private sealed class LockZooKeeperOperation : ZooKeeperOperation
		{
			private readonly WriteLock outerInstance;

			public LockZooKeeperOperation(WriteLock outerInstance)
			{
				this.outerInstance = outerInstance;
			}


			/// <summary>
			/// find if we have been created earler if not create our node
			/// </summary>
			/// <param name="prefix"> the prefix node </param>
			/// <param name="zookeeper"> teh zookeeper client </param>
			/// <param name="dir"> the dir paretn </param>
			/// <exception cref="KeeperException"> </exception>
			private async Task findPrefixInChildren(string prefix, ZooKeeper zookeeper, string dir)
			{
				IList<string> names = (await zookeeper.getChildrenAsync(dir)).Children;
				foreach (string name in names)
				{
					if (name.StartsWith(prefix, StringComparison.Ordinal))
					{
						outerInstance.Id = name;
						if (LOG.isDebugEnabled())
						{
							LOG.debug("Found id created last time: " + outerInstance.Id);
						}
						break;
					}
				}
				if (outerInstance.Id == null)
				{
					outerInstance.Id = await zookeeper.createAsync(dir + "/" + prefix, outerInstance.data, outerInstance.Acl, CreateMode.EPHEMERAL_SEQUENTIAL);

                    if (LOG.isDebugEnabled())
					{
						LOG.debug("Created id: " + outerInstance.Id);
					}
				}

			}

			/// <summary>
			/// the command that is run and retried for actually 
			/// obtaining the lock </summary>
			/// <returns> if the command was successful or not </returns>
			public async Task<bool> execute()
			{
				do
				{
					if (outerInstance.Id == null)
					{
						long sessionId = outerInstance.zookeeper.getSessionId();
						string prefix = "x-" + sessionId + "-";
						// lets try look up the current ID if we failed 
						// in the middle of creating the znode
						await findPrefixInChildren(prefix, outerInstance.zookeeper, outerInstance.dir);
						outerInstance.idName = new ZNodeName(outerInstance.Id);
					}
					if (outerInstance.Id != null)
					{
						List<string> names = (await outerInstance.zookeeper.getChildrenAsync(outerInstance.dir).ConfigureAwait(false)).Children;
						if (names.Count == 0)
						{
							LOG.warn("No children in: " + outerInstance.dir + " when we've just " + "created one! Lets recreate it...");
							// lets force the recreation of the id
							outerInstance.Id = null;
						}
						else
						{
							// lets sort them explicitly (though they do seem to come back in order ususally :)
							SortedSet<ZNodeName> sortedNames = new SortedSet<ZNodeName>();
							foreach (string name in names)
							{
								sortedNames.Add(new ZNodeName(outerInstance.dir + "/" + name));
							}
							outerInstance.ownerId = sortedNames.Min.Name;
                            SortedSet<ZNodeName> lessThanMe = new SortedSet<ZNodeName>();

						    foreach (ZNodeName name in sortedNames) {
						        if (outerInstance.idName.CompareTo(name) > 0) lessThanMe.Add(name);
                                else break;
						    }

						    if (lessThanMe.Count > 0)
							{
								ZNodeName lastChildName = lessThanMe.Max;
								outerInstance.lastChildId = lastChildName.Name;
								if (LOG.isDebugEnabled())
								{
									LOG.debug("watching less than me node: " + outerInstance.lastChildId);
								}
								Stat stat = await outerInstance.zookeeper.existsAsync(outerInstance.lastChildId, new LockWatcher(outerInstance));
								if (stat != null)
								{
									return false;
								}
							    LOG.warn("Could not find the" + " stats for less than me: " + lastChildName.Name);
							}
							else
							{
								if (outerInstance.Owner)
								{
								    lock (outerInstance.callback) {
								        if (outerInstance.callback != null) {
								            outerInstance.callback.lockAcquired();
								        }
								    }
								    return true;
								}
							}
						}
					}
				} while (outerInstance.Id == null);
				return false;
			}
		}

		/// <summary>
		/// Attempts to acquire the exclusive write lock returning whether or not it was
		/// acquired. Note that the exclusive lock may be acquired some time later after
		/// this method has been invoked due to the current lock owner going away.
		/// </summary>
		public async Task<bool> Lock()
		{
            using(await lockable.LockAsync())
			{
				if (Closed)
				{
					return false;
				}
				await ensurePathExists(dir);
        
				return await retryOperation(zop);
			}
		}

		/// <summary>
		/// return the parent dir for lock </summary>
		/// <returns> the parent dir used for locks. </returns>
		public string Dir
		{
			get
			{
				return dir;
			}
		}

		/// <summary>
		/// Returns true if this node is the owner of the
		///  lock (or the leader)
		/// </summary>
		public bool Owner
		{
			get
			{
				return Id != null && ownerId != null && Id.Equals(ownerId);
			}
		}

		/// <summary>
		/// return the id for this lock </summary>
		/// <returns> the id for this lock </returns>
		public string Id { get; private set; }
	}


}