using System;
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

        [Fact]
        public async Task testSync()
        {
            LOG.info("Starting ZK:" + DateTime.Now);

            ZooKeeper zk = await createClient();

            LOG.info("Beginning test:" + DateTime.Now);
            for (var i = 0; i < 100; i++)
            {
                await zk.createAsync("/test" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            await zk.sync("/test");
            for (var i = 0; i < 100; i++)
            {
                await zk.deleteAsync("/test" + i, 0);
            }
            for (var i = 0; i < 100; i++)
            {
                await zk.getChildrenAsync("/", false);
            }
            for (var i = 0; i < 100; i++)
            {
                await zk.getChildrenAsync("/", false);
            }
            LOG.info("Submitted all operations:" + DateTime.Now);
        }
    }
}