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

using System.Threading;
using NUnit.Framework;
using org.apache.utils;

namespace org.apache.zookeeper.test
{
	internal sealed class NullDataTest : ClientBase {

        [Test]
		public void testNullData()
		{
			const string path = "/SIZE";
			ZooKeeper zk = null;
			zk = createClient();
			try {
                ManualResetEventSlim cn = new ManualResetEventSlim(false);
				zk.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
				// try sync zk exists 
				zk.exists(path, false);
                zk.existsAsync(path, false).ContinueWith(t => { cn.Set(); });
                cn.Wait(10 * 1000);
				Assert.assertSame(true, cn.IsSet);
			}
			finally
			{
				if (zk != null)
				{
					zk.close();
				}
			}

		}
	}

}