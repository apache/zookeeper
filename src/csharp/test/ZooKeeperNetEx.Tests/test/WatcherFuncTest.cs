using System.Collections.Concurrent;
﻿using System;
using System.Collections.Generic;
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
    public sealed class WatcherFuncTest : ClientBase
	{
		private sealed class SimpleWatcher : CountdownWatcher
		{
		    private readonly BlockingCollection<WatchedEvent> events = new BlockingCollection<WatchedEvent>();
		    private readonly ManualResetEventSlim latch;

            public SimpleWatcher(ManualResetEventSlim latch)
			{
				this.latch = latch;
			}

		    public async override Task process(WatchedEvent @event) 
            {
		        await base.process(@event);
                if (@event.getState() == Event.KeeperState.SyncConnected)
                {
                    if (latch != null)
                    {
                        latch.Set();
                    }
                }

                if (@event.get_Type() == Event.EventType.None)
                {
                    return;
                }
                try
                {
                    events.Add(@event);
                }
                catch
                {
                    Assert.assertTrue("interruption unexpected", false);
                }
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
        private volatile ManualResetEventSlim client_latch;
		private ZooKeeper client;
		private SimpleWatcher lsnr_dwatch;
		private volatile ManualResetEventSlim lsnr_latch;
		private ZooKeeper lsnr;

		private IList<Watcher.Event.EventType> expected;


		public WatcherFuncTest()
		{
            client_latch = new ManualResetEventSlim(false);
			client_dwatch = new SimpleWatcher(client_latch);
			client = createClient(client_dwatch);

            lsnr_latch = new ManualResetEventSlim(false);
			lsnr_dwatch = new SimpleWatcher(lsnr_latch);
			lsnr = createClient(lsnr_dwatch);

			expected = new List<Watcher.Event.EventType>();
		}


        public override void Dispose()
        {
			client.close();
			lsnr.close();
            base.Dispose();
        }

		private void verify()
		{
			lsnr_dwatch.verify(expected);
			expected.Clear();
		}

        [Fact]
		public void testExistsSync()
		{
			Assert.assertNull(lsnr.exists("/foo", true));
			Assert.assertNull(lsnr.exists("/foo/bar", true));

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);
			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);

			verify();

			Assert.assertNotNull(lsnr.exists("/foo", true));
			Assert.assertNotNull(lsnr.exists("/foo/bar", true));

			try
			{
				Assert.assertNull(lsnr.exists("/car", true));
				client.setData("/car", "missing".getBytes(), -1);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/car", e.getPath());
			}

			try
			{
				Assert.assertNull(lsnr.exists("/foo/car", true));
				client.setData("/foo/car", "missing".getBytes(), -1);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/car", e.getPath());
			}

			client.setData("/foo", "parent".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			client.setData("/foo/bar", "child".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);

			verify();

			Assert.assertNotNull(lsnr.exists("/foo", true));
			Assert.assertNotNull(lsnr.exists("/foo/bar", true));

			client.delete("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			client.delete("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public void testGetDataSync()
		{
			try
			{
				lsnr.getData("/foo", true, null);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				lsnr.getData("/foo/bar", true, null);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getData("/foo", true, null));
			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getData("/foo/bar", true, null));

			client.setData("/foo", "parent".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			client.setData("/foo/bar", "child".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);

			verify();

			Assert.assertNotNull(lsnr.getData("/foo", true, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", true, null));

			client.delete("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			client.delete("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public void testGetChildrenSync()
		{
			try
			{
				lsnr.getChildren("/foo", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				lsnr.getChildren("/foo/bar", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getChildren("/foo", true));

			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", true));


			client.setData("/foo", "parent".getBytes(), -1);
			client.setData("/foo/bar", "child".getBytes(), -1);


			Assert.assertNotNull(lsnr.exists("/foo", true));

			Assert.assertNotNull(lsnr.getChildren("/foo", true));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", true));

			client.delete("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted); // /foo/bar childwatch
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			client.delete("/foo", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);

			verify();
		}

        [Fact]
		public void testExistsSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher(null);
			SimpleWatcher w2 = new SimpleWatcher(null);
			SimpleWatcher w3 = new SimpleWatcher(null);
			SimpleWatcher w4 = new SimpleWatcher(null);

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			Assert.assertNull(lsnr.exists("/foo", true));
			Assert.assertNull(lsnr.exists("/foo", w1));

			Assert.assertNull(lsnr.exists("/foo/bar", w2));
			Assert.assertNull(lsnr.exists("/foo/bar", w3));
			Assert.assertNull(lsnr.exists("/foo/bar", w3));
			Assert.assertNull(lsnr.exists("/foo/bar", w4));

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeCreated);
			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			e2.Add(Watcher.Event.EventType.NodeCreated);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			// default not registered
			Assert.assertNotNull(lsnr.exists("/foo", w1));

			Assert.assertNotNull(lsnr.exists("/foo/bar", w2));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w3));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w4));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w4));

			client.setData("/foo", "parent".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			client.setData("/foo/bar", "child".getBytes(), -1);
			e2.Add(Watcher.Event.EventType.NodeDataChanged);

			lsnr_dwatch.verify(new List<Watcher.Event.EventType>()); // not reg so should = 0
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			Assert.assertNotNull(lsnr.exists("/foo", true));
			Assert.assertNotNull(lsnr.exists("/foo", w1));
			Assert.assertNotNull(lsnr.exists("/foo", w1));

			Assert.assertNotNull(lsnr.exists("/foo/bar", w2));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w2));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w3));
			Assert.assertNotNull(lsnr.exists("/foo/bar", w4));

			client.delete("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			client.delete("/foo", -1);
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
		public void testGetDataSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher(null);
			SimpleWatcher w2 = new SimpleWatcher(null);
			SimpleWatcher w3 = new SimpleWatcher(null);
			SimpleWatcher w4 = new SimpleWatcher(null);

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			try
			{
				lsnr.getData("/foo", w1, null);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				lsnr.getData("/foo/bar", w2, null);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getData("/foo", true, null));
			Assert.assertNotNull(lsnr.getData("/foo", w1, null));
			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getData("/foo/bar", w2, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w3, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w4, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w4, null));

			client.setData("/foo", "parent".getBytes(), -1);
			expected.Add(Watcher.Event.EventType.NodeDataChanged);
			client.setData("/foo/bar", "child".getBytes(), -1);
			e2.Add(Watcher.Event.EventType.NodeDataChanged);

			lsnr_dwatch.verify(expected);
			w1.verify(expected);
			w2.verify(e2);
			w3.verify(e2);
			w4.verify(e2);
			expected.Clear();
			e2.Clear();

			Assert.assertNotNull(lsnr.getData("/foo", true, null));
			Assert.assertNotNull(lsnr.getData("/foo", w1, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w2, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w3, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w3, null));
			Assert.assertNotNull(lsnr.getData("/foo/bar", w4, null));

			client.delete("/foo/bar", -1);
			expected.Add(Watcher.Event.EventType.NodeDeleted);
			client.delete("/foo", -1);
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
		public void testGetChildrenSyncWObj()
		{
			SimpleWatcher w1 = new SimpleWatcher(null);
			SimpleWatcher w2 = new SimpleWatcher(null);
			SimpleWatcher w3 = new SimpleWatcher(null);
			SimpleWatcher w4 = new SimpleWatcher(null);

			IList<Watcher.Event.EventType> e2 = new List<Watcher.Event.EventType>();

			try
			{
				lsnr.getChildren("/foo", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo", e.getPath());
			}
			try
			{
				lsnr.getChildren("/foo/bar", true);
				Assert.fail();
			}
			catch (KeeperException e)
			{
				Assert.assertEquals(KeeperException.Code.NONODE, e.getCode());
				Assert.assertEquals("/foo/bar", e.getPath());
			}

			client.create("/foo", "parent".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			Assert.assertNotNull(lsnr.getChildren("/foo", true));
			Assert.assertNotNull(lsnr.getChildren("/foo", w1));

			client.create("/foo/bar", "child".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w2));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w2));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w3));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w4));


			client.setData("/foo", "parent".getBytes(), -1);
			client.setData("/foo/bar", "child".getBytes(), -1);


			Assert.assertNotNull(lsnr.exists("/foo", true));
			Assert.assertNotNull(lsnr.exists("/foo", w1));
			Assert.assertNotNull(lsnr.exists("/foo", true));
			Assert.assertNotNull(lsnr.exists("/foo", w1));

			Assert.assertNotNull(lsnr.getChildren("/foo", true));
			Assert.assertNotNull(lsnr.getChildren("/foo", w1));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w2));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w3));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w4));
			Assert.assertNotNull(lsnr.getChildren("/foo/bar", w4));

			client.delete("/foo/bar", -1);
			e2.Add(Watcher.Event.EventType.NodeDeleted); // /foo/bar childwatch
			expected.Add(Watcher.Event.EventType.NodeChildrenChanged); // /foo
			client.delete("/foo", -1);
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