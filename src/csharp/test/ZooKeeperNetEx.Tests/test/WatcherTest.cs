using System.Collections.Concurrent;
using System.Threading;
using System.Threading.Tasks;
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
    public class WatcherTest : ClientBase
	{
        private class MyWatcher : CountdownWatcher {
            internal readonly BlockingCollection<WatchedEvent> events =
            new BlockingCollection<WatchedEvent>();

        public override async Task process(WatchedEvent @event) {
            await base.process(@event);
            if (@event.get_Type() != Event.EventType.None) {
                events.Add(@event);
            }
        }
    }

		// <summary>
		// Verify that we get all of the events we expect to get. This particular
		// case verifies that we see all of the data events on a particular node.
		// There was a bug (ZOOKEEPER-137) that resulted in events being dropped
		// in some cases (timing).
		// </summary>
		// <exception cref="IOException"> </exception>
		// <exception cref="InterruptedException"> </exception>
		// <exception cref="KeeperException"> </exception>


        [Fact]
		public void testWatcherCorrectness()
		{
			ZooKeeper zk = null;
			try
			{
				MyWatcher watcher = new MyWatcher();
				zk = createClient(watcher);

				string[] names = new string[10];
				for (int i = 0; i < names.Length; i++)
				{
					string name = zk.create("/tc-", "initialvalue".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
					names[i] = name;

					Stat stat = new Stat();
					zk.getData(name, watcher, stat);
					zk.setData(name, "new".UTF8getBytes(), stat.getVersion());
					stat = zk.exists(name, watcher);
					zk.delete(name, stat.getVersion());
				}

				for (int i = 0; i < names.Length; i++)
				{
					string name = names[i];
					WatchedEvent @event = watcher.events.poll(10* 1000);
					Assert.assertEquals(name, @event.getPath());
					Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, @event.get_Type());
					Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
					@event = watcher.events.poll(10* 1000);
					Assert.assertEquals(name, @event.getPath());
					Assert.assertEquals(Watcher.Event.EventType.NodeDeleted, @event.get_Type());
					Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
				}
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