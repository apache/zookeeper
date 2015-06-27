using System.Collections.Generic;
using NUnit.Framework;
using org.apache.utils;

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
    [TestFixture]
    internal class ZooKeeperTest : ClientBase
	{
        [Test]
		public void testDeleteRecursive()
		{
			ZooKeeper zk = createClient();
			// making sure setdata works on /
            zk.setData("/", "some".getBytes(), -1);
            zk.create("/a", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/a/b", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/a/b/v", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/a/b/v/1", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/a/c", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            zk.create("/a/c/v", "some".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			IList<string> children = zk.getChildren("/a", false);

            Assert.assertEquals("2 children - b & c should be present ", children.Count, 2);
            Assert.assertTrue(children.Contains("b"));
            Assert.assertTrue(children.Contains("c"));

            ZKUtil.deleteRecursiveAsync(zk, "/a").Wait();
            Assert.assertNull(zk.exists("/a", null));
		}

	}

}