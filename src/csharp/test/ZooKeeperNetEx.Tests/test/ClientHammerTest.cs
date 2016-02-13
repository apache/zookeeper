using System;
using System.Collections.Generic;
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
    public sealed class ClientHammerTest : ClientBase
	{
		private static readonly ILogProducer LOG = TypeLogger<ClientHammerTest>.Instance;
        private static readonly byte[] b = new byte[256];

        private const int HAMMERTHREAD_LATENCY = 5;

        private async Task GetBasicHammerTask(string prefix, int count)
        {
            ZooKeeper zk = await createClient();
            for (int current = 0; current < count; current++)
            {
                // Simulate a bit of network latency...
                await Task.Delay(HAMMERTHREAD_LATENCY);
                await zk.createAsync(prefix + current, b, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.warn("created:" + prefix + current);
            }
            await zk.closeAsync();
        }

        private async Task GetSuperHammerTask(string prefix, int count)
        {
            for (int current = 0; current < count; current++)
            {
                ZooKeeper zk = await createClient();
                await zk.createAsync(prefix + current, b, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                await zk.closeAsync();
                LOG.warn("created:" + prefix + current);
            }
        }


        // <summary>
		// Separate tasks each creating a number of nodes. Each task
		// is using a non-shared (owned by task) client for all node creations. </summary>
        [Fact]
        public async Task testHammerBasic()
        {
            int threadCount = 10;
            int childCount = 1000;
            Task[] tasks = new Task[threadCount];
            for (int i = 0; i < tasks.Length; i++)
            {
                ZooKeeper zk = await createClient();
                string prefix = "/test-" + i;
                await zk.createAsync(prefix, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                await zk.closeAsync();
                LOG.warn("created:" + prefix);
                prefix += "/";
                tasks[i] = GetBasicHammerTask(prefix, childCount);
            }

            await verifyHammer(tasks, childCount);
        }

        // <summary>
		// Separate tasks each creating a number of nodes. Each task
		// is creating a new client for each node creation. </summary>
        [Fact]
        public async Task testHammerSuper()
        {
            const int threadCount = 5;
            const int childCount = 10;

            Task[] tasks = new Task[threadCount];
            for (int i = 0; i < tasks.Length; i++)
            {
                string prefix = "/test-" + i;
                ZooKeeper zk = await createClient();
                await zk.createAsync(prefix, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                await zk.closeAsync();
                LOG.warn("created:" + prefix);
                prefix += "/";
                tasks[i] = GetSuperHammerTask(prefix, childCount);
            }

            await verifyHammer(tasks, childCount);
        }


        private async Task verifyHammer(Task[] tasks, int childCount)
		{
			// look for the clients to finish their create operations
			LOG.warn("Starting check for completed hammers");
			Assert.assertTrue(await Task.WhenAll(tasks).WithTimeout(30000));
            
			ZooKeeper zk = await createClient();
	        for (int i = 0; i < tasks.Length; i++)
	        {
	            LOG.info("Doing task: " + i + " " + DateTime.Now);
	            IList<string> children = (await zk.getChildrenAsync("/test-" + i, false)).Children;
	            Assert.assertEquals(childCount, children.Count);
	        }
            LOG.warn("Done");
        }
	}
}