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

	    [Fact]
		public Task testRun()
		{
			return runTest(3);
		}

	    private sealed class LockCallback : LockListener
		{
			private readonly AsyncManualResetEvent asyncManualResetEvent;

			public LockCallback(AsyncManualResetEvent asyncManualResetEvent)
			{
				this.asyncManualResetEvent = asyncManualResetEvent;
			}

			public Task lockAcquired()
			{
                asyncManualResetEvent.Set();
			    return Task.FromResult(0);
			}

			public Task lockReleased()
			{
                return Task.FromResult(0);
            }

		}

	    private async Task runTest(int count)
		{
			var nodes = new WriteLock[count];
	        var asyncManualResetEvent = new AsyncManualResetEvent();
			for (int i = 0; i < count; i++)
			{
				ZooKeeper keeper = await createClient();
				WriteLock leader = new WriteLock(keeper, "/test", null);
				leader.setLockListener(new LockCallback(asyncManualResetEvent));
				nodes[i] = leader;

				await leader.Lock();
			}

			// lets wait for any previous leaders to die and one of our new
			// nodes to become the new leader
            await asyncManualResetEvent.WaitAsync().WithTimeout(30*1000);

			WriteLock first = nodes[0];
			dumpNodes(nodes,count);

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
			    asyncManualResetEvent.Reset();
			    await first.unlock();
                await asyncManualResetEvent.WaitAsync().WithTimeout(30 * 1000);
                WriteLock second = nodes[1];
			    dumpNodes(nodes, count);
			    // lets assert that the first election is the leader
			    Assert.assertTrue("The second znode should be the leader " + second.Id, second.Owner);

			    for (int i = 2; i < count; i++)
			    {
			        WriteLock node = nodes[i];
			        Assert.assertFalse("Node should not be the leader " + node.Id, node.Owner);
			    }
			}
		}

	    private static void dumpNodes(WriteLock[] nodes, int count)
		{
			for (int i = 0; i < count; i++)
			{
				WriteLock node = nodes[i];
                LOG.debug("node: " + i + " id: " + node.Id + " is leader: " + node.Owner);
			}
		}
	}

}