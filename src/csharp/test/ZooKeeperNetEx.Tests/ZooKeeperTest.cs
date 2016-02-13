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
namespace org.apache.zookeeper
{
	/// 
	/// <summary>
	/// Testing Zookeeper public methods
	/// </summary>
	public class ZooKeeperTest : ClientBase
	{
        [Fact]
		public async Task testDeleteRecursive()
		{
			ZooKeeper zk = await createClient();
			// making sure setdata works on /
            await zk.setDataAsync("/", "some".UTF8getBytes(), -1);
            await zk.createAsync("/a", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            await zk.createAsync("/a/b", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            await zk.createAsync("/a/b/v", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            await zk.createAsync("/a/b/v/1", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            await zk.createAsync("/a/c", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            await zk.createAsync("/a/c/v", "some".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			IList<string> children = (await zk.getChildrenAsync("/a", false)).Children;

            Assert.assertEquals("2 children - b & c should be present ", children.Count, 2);
            Assert.assertTrue(children.Contains("b"));
            Assert.assertTrue(children.Contains("c"));

            ZKUtil.deleteRecursiveAsync(zk, "/a").GetAwaiter().GetResult();
            Assert.assertNull(await zk.existsAsync("/a", null));
		}

	}

}