using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using org.apache.utils;
using org.apache.zookeeper.data;
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
    public sealed class ACLCountTest : ClientBase
	{
		/// 
		/// <summary>
		/// Create a node and add 4 ACL values to it, but there are only 2 unique ACL values,
		/// and each is repeated once:
		/// 
		///   ACL(ZooDefs.Perms.READ,ZooDefs.Ids.ANYONE_ID_UNSAFE);
		///   ACL(ZooDefs.Perms.ALL,ZooDefs.Ids.AUTH_IDS);
		///   ACL(ZooDefs.Perms.READ,ZooDefs.Ids.ANYONE_ID_UNSAFE);
		///   ACL(ZooDefs.Perms.ALL,ZooDefs.Ids.AUTH_IDS);
		/// 
		/// Even though we've added 4 ACL values, there should only be 2 ACLs for that node,
		/// since there are only 2 *unique* ACL values.
		/// </summary>

        [Fact]
		public async Task testAclCount() {

		    List<ACL> CREATOR_ALL_AND_WORLD_READABLE = new List<ACL>
		    {
		        new ACL((int) ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE),
		        new ACL((int) ZooDefs.Perms.ALL, ZooDefs.Ids.AUTH_IDS),
		        new ACL((int) ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE),
		        new ACL((int) ZooDefs.Perms.ALL, ZooDefs.Ids.AUTH_IDS)
		    };
		        var zk = await createClient();

		        zk.addAuthInfo("digest", "pat:test".UTF8getBytes());
		        await zk.setACLAsync("/", ZooDefs.Ids.CREATOR_ALL_ACL, -1);

		        await zk.createAsync("/path", "/path".UTF8getBytes(), CREATOR_ALL_AND_WORLD_READABLE, CreateMode.PERSISTENT);
		        IList<ACL> acls = (await zk.getACLAsync("/path")).Acls;
		        Assert.assertEquals(2, acls.Count);
		        await zk.setACLAsync("/", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
                await zk.setACLAsync("/path", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
		}

	}

}