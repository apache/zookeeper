using System;
using System.Collections.Generic;
using System.Threading;

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
    public sealed class ClientHammerTest : ClientBase
	{
		private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(ClientHammerTest));

		private const int HAMMERTHREAD_LATENCY = 5;

	    private abstract class HammerThread : BackgroundThread
		{
			protected internal readonly int count;
			protected internal volatile int current;

		    protected HammerThread(string name, int count) : base(name,LOG)
			{
				this.count = count;
			}
		}

		private sealed class BasicHammerThread : HammerThread
		{
		    private readonly ZooKeeper zk;
		    private readonly string prefix;

			internal BasicHammerThread(string name, ZooKeeper zk, string prefix, int count) : base(name, count)
			{
				this.zk = zk;
				this.prefix = prefix;
			}

		    protected override void run()
			{
				byte[] b = new byte[256];
				try
				{
					for (; current < count; current++)
					{
						// Simulate a bit of network latency...
						Thread.Sleep(HAMMERTHREAD_LATENCY);
						zk.create(prefix + current, b, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
					}
				}
				catch (Exception t)
				{
					LOG.error("Client create operation Assert.failed", t);
				}
				finally
				{
					zk.close();
				}
			}
		}

		private sealed class SuperHammerThread : HammerThread
		{
		    private readonly ClientHammerTest parent;
		    private readonly string prefix;

			internal SuperHammerThread(string name, ClientHammerTest parent, string prefix, int count) : base(name, count)
			{
				this.parent = parent;
				this.prefix = prefix;
			}

		    protected override void run()
		    {
		        byte[] b = new byte[256];
		        for (; current < count; current++)
		        {
		            ZooKeeper zk = parent.createClient();
		            try
		            {
		                zk.create(prefix + current, b, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		            }
		            finally
		            {
		                zk.close();
		            }
		        }
		    }
		}

		// <summary>
		// Separate threads each creating a number of nodes. Each thread
		// is using a non-shared (owned by thread) client for all node creations. </summary>
		// <exception cref="Throwable"> </exception>
        [Fact]
		public void testHammerBasic()
		{
			runHammer(10, 1000);
		}

        private void runHammer(int threadCount, int childCount)
		{
			try
			{
				HammerThread[] threads = new HammerThread[threadCount];
				long start = TimeHelper.ElapsedMiliseconds;
				for (int i = 0; i < threads.Length; i++)
				{
					ZooKeeper zk = createClient();
					string prefix = "/test-" + i;
					zk.create(prefix, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
					prefix += "/";
					HammerThread thread = new BasicHammerThread("BasicHammerThread-" + i, zk, prefix, childCount);
					thread.start();

					threads[i] = thread;
				}

				verifyHammer(start, threads, childCount);
			}
			catch (Exception t)
			{
				LOG.error("test Assert.failed", t);
				throw;
			}
		}

		// <summary>
		// Separate threads each creating a number of nodes. Each thread
		// is creating a new client for each node creation. </summary>
		// <exception cref="Throwable"> </exception>
        [Fact]
		public void testHammerSuper()
		{
			try
			{
				const int threadCount = 5;
				const int childCount = 10;

				HammerThread[] threads = new HammerThread[threadCount];
				long start = TimeHelper.ElapsedMiliseconds;
				for (int i = 0; i < threads.Length; i++)
				{
					string prefix = "/test-" + i;
					{
						ZooKeeper zk = createClient();
						try
						{
							zk.create(prefix, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
						}
						finally
						{
							zk.close();
						}
					}
					prefix += "/";
					HammerThread thread = new SuperHammerThread("SuperHammerThread-" + i, this, prefix, childCount);
					thread.start();

					threads[i] = thread;
				}

				verifyHammer(start, threads, childCount);
			}
			catch (Exception t)
			{
				LOG.error("test Assert.failed", t);
				throw;
			}
		}


	    private void verifyHammer(long start, HammerThread[] threads, int childCount)
		{
			// look for the clients to finish their create operations
			LOG.info("Starting check for completed hammers");
			int workingCount = threads.Length;
			for (int i = 0; i < 120; i++)
			{
				Thread.Sleep(10000);
				foreach (HammerThread h in threads)
				{
					if (!h.isAlive() || h.current == h.count)
					{
						workingCount--;
					}
				}
				if (workingCount == 0)
				{
					break;
				}
				workingCount = threads.Length;
			}
			if (workingCount > 0)
			{
				foreach (HammerThread h in threads)
				{
					LOG.warn(h.getName() + " never finished creation, current:" + h.current);
				}
			}
			else
			{
				LOG.info("Hammer threads completed creation operations");
			}

			foreach (HammerThread h in threads)
			{
				const int safetyFactor = 3;
				verifyThreadTerminated(h, threads.Length * childCount * HAMMERTHREAD_LATENCY * safetyFactor);
			}
			LOG.info(DateTime.Now + " Total time " + (TimeHelper.ElapsedMiliseconds - start));

			ZooKeeper zk = createClient();
			try
			{
				LOG.info("******************* Connected to ZooKeeper" + DateTime.Now);
				for (int i = 0; i < threads.Length; i++)
				{
					LOG.info("Doing thread: " + i + " " + DateTime.Now);
					IList<string> children = zk.getChildren("/test-" + i, false);
					Assert.assertEquals(childCount, children.Count);
					children = zk.getChildren("/test-" + i, false, null);
					Assert.assertEquals(childCount, children.Count);
				}
				for (int i = 0; i < threads.Length; i++)
				{
					IList<string> children = zk.getChildren("/test-" + i, false);
					Assert.assertEquals(childCount, children.Count);
					children = zk.getChildren("/test-" + i, false, null);
					Assert.assertEquals(childCount, children.Count);
				}
			}
			finally
			{
				zk.close();
			}
		}

        private static void verifyThreadTerminated(BackgroundThread thread, int millis)
        {
            thread.join(millis);
            if (thread.isAlive())
            {
                LOG.error("Thread " + thread.getName());
                Assert.assertFalse("thread " + thread.getName()
                                   + " still alive after join", true);
            }
        }

        private abstract class BackgroundThread
        {
            private readonly Thread m_Thread;

            protected BackgroundThread(string name, TraceLogger log)
            {
                m_Thread = new Thread(() => RunAndLogException(run, log)) { Name = name, IsBackground = true };
            }

            protected abstract void run();

            public void start()
            {
                m_Thread.Start();
            }

            public void join(int wait)
            {
                m_Thread.Join(wait);
            }

            public bool isAlive()
            {
                return m_Thread.IsAlive;
            }

            public string getName()
            {
                return m_Thread.Name;
            }

            private static void RunAndLogException(Action action, TraceLogger log)
            {
                try
                {
                    action();
                }
                catch (Exception e)
                {
                    log.error(e);
                }
            }
        }
	}
}