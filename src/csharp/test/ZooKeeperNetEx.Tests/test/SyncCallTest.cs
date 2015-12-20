using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

using org.apache.utils;
using Xunit;

// <summary>
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//     http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>

namespace org.apache.zookeeper.test
{
    public sealed class SyncCallTest : ClientBase
	{
        private static readonly ILogProducer LOG = TypeLogger<SyncCallTest>.Instance;
		private int opsCount;

	    private ManualResetEventSlim countFinished;

	    private readonly List<int> results = new List<int>();
	    private const int limit = 100 + 1 + 100 + 100;

        [Fact]
	    public void testSync()
		{
			try
			{
				LOG.info("Starting ZK:" + (DateTime.Now));
                countFinished = new ManualResetEventSlim(false);
                opsCount = limit;
				ZooKeeper zk = createClient();

				LOG.info("Beginning test:" + (DateTime.Now));
				for (int i = 0; i < 100; i++)
				{
					zk.createAsync("/test" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT).
                        ContinueWith(processResult);
				}
				zk.sync("/test").ContinueWith(processResult);
				for (int i = 0; i < 100; i++)
				{
					zk.deleteAsync("/test" + i, 0).ContinueWith(processResult);
				}
				for (int i = 0; i < 100; i++)
				{
					zk.getChildrenAsync("/", new NullWatcher()).ContinueWith(processResult);
				}
				for (int i = 0; i < 100; i++)
				{
                    zk.getChildrenAsync("/", new NullWatcher()).ContinueWith(processResult);
				}
				LOG.info("Submitted all operations:" + (DateTime.Now));

				if (!countFinished.Wait(10000))
				{
                    Interlocked.MemoryBarrier();
					Assert.fail("Haven't received all confirmations" + opsCount);
				}

				for (int i = 0; i < limit ; i++)
				{
					lock (results) {
					    Assert.assertEquals(0, results[i]);
					}
				}

			}
			catch (IOException e)
			{
                LOG.error(e.ToString());
			}
		}


	    private void processResult(Task task) {
	        if (task.Exception != null) {
	        }
	        lock (results) {
	            results.Add(0);
	        }
	        if (Interlocked.Decrement(ref opsCount) <= 0)
	            countFinished.Set();
	    }
	}

}