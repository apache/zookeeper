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

using org.apache.utils;
using Xunit;

namespace org.apache.zookeeper.test
{
    public sealed class ACLRootTest : ClientBase
	{
        [Fact]
		public void testRootAcl()
		{
			ZooKeeper zk = createClient();
			try
			{
				// set auth using digest
				zk.addAuthInfo("digest", "pat:test".UTF8getBytes());
				zk.setACL("/", ZooDefs.Ids.CREATOR_ALL_ACL, -1);
				zk.getData("/", false, null);
				zk.close();
				// verify no access
				zk = createClient();
				try
				{
					zk.getData("/", false, null);
					Assert.fail("validate auth");
				}
				catch (KeeperException.NoAuthException)
				{
					// expected
				}
				try
				{
					zk.create("/apps", null, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
					Assert.fail("validate auth");
				}
				catch (KeeperException.InvalidACLException)
				{
					// expected
				}
				zk.addAuthInfo("digest", "world:anyone".UTF8getBytes());
				try
				{
					zk.create("/apps", null, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
					Assert.fail("validate auth");
				}
				catch (KeeperException.NoAuthException)
				{
					// expected
				}
				zk.close();
				// verify access using original auth
				zk = createClient();
				zk.addAuthInfo("digest", "pat:test".UTF8getBytes());
				zk.getData("/", false, null);
				zk.create("/apps", null, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
				zk.delete("/apps", -1);
				// reset acl (back to open) and verify accessible again
				zk.setACL("/", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
				zk.close();
				zk = createClient();
				zk.getData("/", false, null);
				zk.create("/apps", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
				try
				{
					zk.create("/apps", null, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
					Assert.fail("validate auth");
				}
				catch (KeeperException.InvalidACLException)
				{
					// expected
				}
				zk.delete("/apps", -1);
				zk.addAuthInfo("digest", "world:anyone".UTF8getBytes());
				zk.create("/apps", null, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
				zk.close();
				zk = createClient();
				zk.delete("/apps", -1);
			}
			finally
			{
				zk.close();
			}
		}
	}

}