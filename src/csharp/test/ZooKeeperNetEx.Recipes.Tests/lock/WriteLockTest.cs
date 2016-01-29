using System.Threading;
using System.Threading.Tasks;
using org.apache.utils;
using Xunit;

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
	/// test for writelock
	/// </summary>
	public sealed class WriteLockTest : ClientBase
	{
        private static readonly ILogProducer LOG = TypeLogger<WriteLockTest>.Instance;

        private WriteLock[] nodes;
	    private ManualResetEventSlim latch = new ManualResetEventSlim(false);

	    [Fact]
		public void testRun()
		{
			runTest(3);
		}

	    private sealed class LockCallback : LockListener
		{
			private readonly WriteLockTest outerInstance;

			public LockCallback(WriteLockTest outerInstance)
			{
				this.outerInstance = outerInstance;
			}

			public Task lockAcquired()
			{
				outerInstance.latch.Set();
			    return Task.FromResult(0);
			}

			public Task lockReleased()
			{
                return Task.FromResult(0);
            }

		}

	    private void runTest(int count)
		{
			nodes = new WriteLock[count];
			for (int i = 0; i < count; i++)
			{
				ZooKeeper keeper = createClient();
				WriteLock leader = new WriteLock(keeper, "/test", null);
				leader.setLockListener(new LockCallback(this));
				nodes[i] = leader;

				leader.Lock().GetAwaiter().GetResult();
			}

			// lets wait for any previous leaders to die and one of our new
			// nodes to become the new leader
			latch.Wait(30*1000);

			WriteLock first = nodes[0];
			dumpNodes(count);

			// lets assert that the first election is the leader
			Assert.assertTrue("The first znode should be the leader " + first.Id, first.Owner);

			for (int i = 1; i < count; i++)
			{
				WriteLock node = nodes[i];
				Assert.assertFalse("Node should not be the leader " + node.Id, node.Owner);
			}

			if (count > 1)
			{
			    LOG.debug("Now killing the leader");
			    // now lets kill the leader
			    latch = new ManualResetEventSlim(false);
			    first.unlock().GetAwaiter().GetResult();
			    latch.Wait(30*1000);
			    WriteLock second = nodes[1];
			    dumpNodes(count);
			    // lets assert that the first election is the leader
			    Assert.assertTrue("The second znode should be the leader " + second.Id, second.Owner);

			    for (int i = 2; i < count; i++)
			    {
			        WriteLock node = nodes[i];
			        Assert.assertFalse("Node should not be the leader " + node.Id, node.Owner);
			    }
			}
		}

	    private void dumpNodes(int count)
		{
			for (int i = 0; i < count; i++)
			{
				WriteLock node = nodes[i];
                LOG.debug("node: " + i + " id: " + node.Id + " is leader: " + node.Owner);
			}
		}

		public override void Dispose()
		{
			if (nodes != null)
			{
				for (int i = 0; i < nodes.Length; i++)
				{
					WriteLock node = nodes[i];
					if (node != null)
					{
						if (i == nodes.Length - 1)
						{
                            LOG.debug("Not closing zookeeper: " + i + " due to bug!");
						}
						else
						{
                            LOG.debug("Closing zookeeper: " + i);
							node.getZookeeper().closeAsync().Wait();
                            LOG.debug("Closed zookeeper: " + i);
						}
					}
				}
			}
			base.Dispose();
		}
	}

}