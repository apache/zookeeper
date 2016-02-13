using System.Collections.Concurrent;
﻿using System;
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
    public sealed class WatcherFuncTest : ClientBase
	{
		private sealed class SimpleWatcher : Watcher
		{
		    private readonly BlockingCollection<WatchedEvent> events = new BlockingCollection<WatchedEvent>();

		    public override Task process(WatchedEvent @event) 
            {
                if (@event.get_Type() != Event.EventType.None)
		        {
		            try
		            {
		                events.Add(@event);
		            }
		            catch
		            {
		                Assert.assertTrue("interruption unexpected", false);
		            }
		        }
		        return CompletedTask;
            }


		    public void verify(IList<Event.EventType> expected)
			{
				WatchedEvent @event;
				int count = 0;
				while (count < expected.Count && (@event = events.poll(30*1000)) != null)
				{
					Assert.assertEquals(expected[count], @event.get_Type());
					count++;
				}
			    Assert.assertEquals(expected.Count, count);
			    while (events.TryTake(out @event)) ;
			}
		}
		private SimpleWatcher client_dwatch;
		private readonly ZooKeeper client;
		private readonly SimpleWatcher lsnr_dwatch;
		private readonly ZooKeeper lsnr;

		private readonly IList<Watcher.Event.EventType> expected;


		public WatcherFuncTest()
		{
			client_dwatch = new SimpleWatcher();
			client = createClient(client_dwatch).Result;

			lsnr_dwatch = new SimpleWatcher();
			lsnr = createClient(lsnr_dwatch).Result;

			expected = new List<Watcher.Event.EventType>();
		}

		private void verify()
		{
			lsnr_dwatch.verify(expected);
			expected.Clear();
		}

        [Fact]
		public async Task testExistsSync()
		{
			Assert.assertNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNull(await lsnr.existsAsync("/foo/bar", true));

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);
			await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);

			verify();

			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", true));

			try
			{
				Assert.assertNull(await lsnr.existsAsync("/car", true));
				await client.setDataAsync("/car", "missing".UTF8getBytes(), -1);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/car", e.getPath());
			}

			try
			{
				Assert.assertNull(await lsnr.existsAsync("/foo/car", true));
				await client.setDataAsync("/foo/car", "missing".UTF8getBytes(), -1);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/car", e.getPath());
			}

			await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);

			verify();

			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", true));

			await client.deleteAsync("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			await client.deleteAsync("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public async Task testGetDataSync()
		{
			try
			{
				await lsnr.getDataAsync("/foo", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				await lsnr.getDataAsync("/foo/bar", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getDataAsync("/foo", true)).Data);
            await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", true)).Data);

            await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);

			verify();

			Assert.assertNotNull((await lsnr.getDataAsync("/foo", true)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", true)).Data);

            await client.deleteAsync("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			await client.deleteAsync("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public async Task testGetChildrenSync()
		{
			try
			{
				await lsnr.getChildrenAsync("/foo", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				await lsnr.getChildrenAsync("/foo/bar", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", true)).Children);

			await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", true)).Children);


            await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);


			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));

			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", true)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", true)).Children);

            await client.deleteAsync("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted); // /foo/bar childwatch
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			await client.deleteAsync("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public async Task testExistsSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher();
			SimpleWatcher w2 = new SimpleWatcher();
			SimpleWatcher w3 = new SimpleWatcher();
			SimpleWatcher w4 = new SimpleWatcher();

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			Assert.assertNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNull(await lsnr.existsAsync("/foo", w1));

			Assert.assertNull(await lsnr.existsAsync("/foo/bar", w2));
			Assert.assertNull(await lsnr.existsAsync("/foo/bar", w3));
			Assert.assertNull(await lsnr.existsAsync("/foo/bar", w3));
			Assert.assertNull(await lsnr.existsAsync("/foo/bar", w4));

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);
			await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			e2.Add(Watcher.Event.EventType.NodeCreated);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			// default not registered
			Assert.assertNotNull(await lsnr.existsAsync("/foo", w1));

			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w2));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w3));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w4));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w4));

			await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);
			e2.Add(Watcher.Event.EventType.NodeDataChanged);

			lsnr_dwatch.verify(new List<Watcher.Event.EventType>()); // not reg so should = 0
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNotNull(await lsnr.existsAsync("/foo", w1));
			Assert.assertNotNull(await lsnr.existsAsync("/foo", w1));

			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w2));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w2));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w3));
			Assert.assertNotNull(await lsnr.existsAsync("/foo/bar", w4));

			await client.deleteAsync("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			await client.deleteAsync("/foo", -1);
			e2.Add(Watcher.Event.EventType.NodeDeleted);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();
		}

        [Fact]
		public async Task testGetDataSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher();
			SimpleWatcher w2 = new SimpleWatcher();
			SimpleWatcher w3 = new SimpleWatcher();
			SimpleWatcher w4 = new SimpleWatcher();

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			try
			{
				await lsnr.getDataAsync("/foo", w1);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				await lsnr.getDataAsync("/foo/bar", w2);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getDataAsync("/foo", true)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo", w1)).Data);
            await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w2)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w3)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w4)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w4)).Data);

            await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);
			e2.Add(Watcher.Event.EventType.NodeDataChanged);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			Assert.assertNotNull((await lsnr.getDataAsync("/foo", true)).Data);
			Assert.assertNotNull((await lsnr.getDataAsync("/foo", w1)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w2)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w3)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w3)).Data);
            Assert.assertNotNull((await lsnr.getDataAsync("/foo/bar", w4)).Data);

            await client.deleteAsync("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			await client.deleteAsync("/foo", -1);
			e2.Add(Watcher.Event.EventType.NodeDeleted);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();
		}

        [Fact]
		public async Task testGetChildrenSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher();
			SimpleWatcher w2 = new SimpleWatcher();
			SimpleWatcher w3 = new SimpleWatcher();
			SimpleWatcher w4 = new SimpleWatcher();

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			try
			{
				await lsnr.getChildrenAsync("/foo", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				await lsnr.getChildrenAsync("/foo/bar", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			await client.createAsync("/foo", "parent".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", true)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", w1)).Children);

            await client.createAsync("/foo/bar", "child".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w2)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w2)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w3)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w4)).Children);


            await client.setDataAsync("/foo", "parent".UTF8getBytes(), -1);
			await client.setDataAsync("/foo/bar", "child".UTF8getBytes(), -1);


			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNotNull(await lsnr.existsAsync("/foo", w1));
			Assert.assertNotNull(await lsnr.existsAsync("/foo", true));
			Assert.assertNotNull(await lsnr.existsAsync("/foo", w1));

			Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", true)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo", w1)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w2)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w3)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w4)).Children);
            Assert.assertNotNull((await lsnr.getChildrenAsync("/foo/bar", w4)).Children);

            await client.deleteAsync("/foo/bar", -1);
			e2.Add(Watcher.Event.EventType.NodeDeleted); // /foo/bar childwatch
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			await client.deleteAsync("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();
		}
	}

}