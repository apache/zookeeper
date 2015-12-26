using System.Collections.Generic;
using org.apache.zookeeper.data;
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

    public sealed class GetChildren2Test : ClientBase
	{
		private ZooKeeper zk;



		public GetChildren2Test()
		{
			zk = createClient();
		}



		public override void Dispose()
		{
			base.Dispose();

			zk.close();
		}



        [Fact]
		public void testChild()
		{
			string name = "/foo";
			zk.create(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			string childname = name + "/bar";
			zk.create(childname, childname.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

			Stat stat = new Stat();
			IList<string> s = zk.getChildren(name, false, stat);

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid() + 1, stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(1, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(0, stat.getEphemeralOwner());
			Assert.assertEquals(name.Length, stat.getDataLength());
			Assert.assertEquals(1, stat.getNumChildren());
			Assert.assertEquals(s.Count, stat.getNumChildren());

			s = zk.getChildren(childname, false, stat);

			Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
			Assert.assertEquals(stat.getCzxid(), stat.getPzxid());
			Assert.assertEquals(stat.getCtime(), stat.getMtime());
			Assert.assertEquals(0, stat.getCversion());
			Assert.assertEquals(0, stat.getVersion());
			Assert.assertEquals(0, stat.getAversion());
			Assert.assertEquals(zk.getSessionId(), stat.getEphemeralOwner());
			Assert.assertEquals(childname.Length, stat.getDataLength());
			Assert.assertEquals(0, stat.getNumChildren());
			Assert.assertEquals(s.Count, stat.getNumChildren());
		}



        [Fact]
		public void testChildren()
		{
			string name = "/foo";
			zk.create(name, name.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

			IList<string> children = new List<string>();
			List<string> children_s = new List<string>();

			for (int i = 0; i < 10; i++)
			{
				string childname = name + "/bar" + i;
				string childname_s = "bar" + i;
				children.Add(childname);
				children_s.Add(childname_s);
			}

			for (int i = 0; i < children.Count; i++)
			{
				string childname = children[i];
				zk.create(childname, childname.UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

				Stat stat = new Stat();
				IList<string> s = zk.getChildren(name, false, stat);

				Assert.assertEquals(stat.getCzxid(), stat.getMzxid());
				Assert.assertEquals(stat.getCzxid() + i + 1, stat.getPzxid());
				Assert.assertEquals(stat.getCtime(), stat.getMtime());
				Assert.assertEquals(i + 1, stat.getCversion());
				Assert.assertEquals(0, stat.getVersion());
				Assert.assertEquals(0, stat.getAversion());
				Assert.assertEquals(0, stat.getEphemeralOwner());
				Assert.assertEquals(name.Length, stat.getDataLength());
				Assert.assertEquals(i + 1, stat.getNumChildren());
				Assert.assertEquals(s.Count, stat.getNumChildren());
			}
			List<string> p = zk.getChildren(name, false, null);
			List<string> c_a = children_s;
			List<string> c_b = p;
			c_a.Sort();
			c_b.Sort();
			Assert.assertEquals(c_a.Count, 10);
			Assert.assertEquals(c_a, c_b);
		}
	}

}