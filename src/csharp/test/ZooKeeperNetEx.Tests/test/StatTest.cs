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

using System.Threading.Tasks;
using org.apache.zookeeper.data;
using org.apache.utils;
using Xunit;

namespace org.apache.zookeeper.test
{
    public sealed class StatTest : ClientBase
	{
        [Fact]
		public async Task testBasic()
		{
            var zk = await createClient();
            const string name = "/foo";
			await zk.createAsync(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            
            var stat=(await zk.getDataAsync(name, false)).Stat;

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(0, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(0, stat.getEphemeralOwner());
			Assert.assertEquals(name.Length, stat.getDataLength());
			Assert.assertEquals(0, stat.getNumChildren());
		}

        [Fact]
		public async Task testChild()
		{
            var zk = await createClient();
            const string name = "/foo";
			await zk.createAsync(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			const string childname = name + "/bar";
			await zk.createAsync(childname, childname.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

            var stat = (await zk.getDataAsync(name, false)).Stat;

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid() + 1, stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(1, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(0, stat.getEphemeralOwner());
			Assert.assertEquals(name.Length, stat.getDataLength());
			Assert.assertEquals(1, stat.getNumChildren());

			stat = (await zk.getDataAsync(childname, false)).Stat;

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(0, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(zk.getSessionId(), stat.getEphemeralOwner());
			Assert.assertEquals(childname.Length, stat.getDataLength());
			Assert.assertEquals(0, stat.getNumChildren());
		}

        [Fact]
		public async Task testChildren()
		{
            var zk = await createClient();
            const string name = "/foo";
			await zk.createAsync(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			for (int i = 0; i < 10; i++)
			{
				string childname = name + "/bar" + i;
				await zk.createAsync(childname, childname.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

			    var stat = (await zk.getDataAsync(name, false)).Stat;

				Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
				Assert.assertEquals(stat.getCzxid() + i + 1, stat.getPzxid());
				Assert.assertEquals(stat.getCtime(), stat.getMtime());
				Assert.assertEquals(i + 1, stat.getCversion());
				Assert.assertEquals(0, stat.getVersion());
				Assert.assertEquals(0, stat.getAversion());
				Assert.assertEquals(0, stat.getEphemeralOwner());
				Assert.assertEquals(name.Length, stat.getDataLength());
				Assert.assertEquals(i + 1, stat.getNumChildren());
			}
		}

        [Fact]
		public async Task testDataSizeChange()
		{
            var zk = await createClient();
            const string name = "/foo";
			await zk.createAsync(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            var stat = (await zk.getDataAsync(name, false)).Stat;

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(0, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(0, stat.getEphemeralOwner());
			Assert.assertEquals(name.Length, stat.getDataLength());
			Assert.assertEquals(0, stat.getNumChildren());

			await zk.setDataAsync(name, (name + name).UTF8getBytes(), -1);

			stat = (await zk.getDataAsync(name, false)).Stat;

			Assert.assertNotEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
			Assert.assertEquals(0, stat.getCversion());
			Assert.assertEquals(1, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(0, stat.getEphemeralOwner());
			Assert.assertEquals(name.Length * 2, stat.getDataLength());
			Assert.assertEquals(0, stat.getNumChildren());
		}
	}

}