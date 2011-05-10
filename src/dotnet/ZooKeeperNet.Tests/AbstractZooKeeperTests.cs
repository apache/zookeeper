/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
namespace ZooKeeperNet.Tests
{
    using System;
    using System.Runtime.CompilerServices;
    using System.Threading;
    using log4net.Config;

    public abstract class AbstractZooKeeperTests
    {
        static AbstractZooKeeperTests()
        {
            XmlConfigurator.Configure();    
        }

        protected static readonly TimeSpan CONNECTION_TIMEOUT = new TimeSpan(0, 0, 0, 0, 10000);

        protected virtual ZooKeeper CreateClient()
        {
            CountdownWatcher watcher = new CountdownWatcher();
            return new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 10000), watcher);
        }

        protected virtual ZooKeeper CreateClient(string node)
        {
            CountdownWatcher watcher = new CountdownWatcher();
            return new ZooKeeper("127.0.0.1:2181" + node, new TimeSpan(0, 0, 0, 10000), watcher);
        }

        protected ZooKeeper CreateClient(IWatcher watcher)
        {
            return new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 10000), watcher);
        }

        protected ZooKeeper CreateClientWithAddress(string address)
        {
            CountdownWatcher watcher = new CountdownWatcher();
            return new ZooKeeper(address, new TimeSpan(0, 0, 0, 10000), watcher);
        }

        public class CountdownWatcher : IWatcher
        {
            readonly ManualResetEvent resetEvent = new ManualResetEvent(false);
            private static readonly object sync = new object();

            volatile bool connected;

            public CountdownWatcher()
            {
                Reset();
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            public void Reset()
            {
                resetEvent.Set();
                connected = false;
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            public virtual void Process(WatchedEvent @event)
            {
                if (@event.State == KeeperState.SyncConnected)
                {
                    connected = true;
                    lock (sync)
                    {
                        Monitor.PulseAll(sync);
                    }
                    resetEvent.Set();
                }
                else
                {
                    connected = false;
                    lock (sync)
                    {
                        Monitor.PulseAll(sync);
                    }
                }
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            bool IsConnected()
            {
                return connected;
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            void waitForConnected(TimeSpan timeout)
            {
                DateTime expire = DateTime.Now + timeout;
                TimeSpan left = timeout;
                while (!connected && left.TotalMilliseconds > 0)
                {
                    lock (sync)
                    {
                        Monitor.TryEnter(sync, left);
                    }
                    left = expire - DateTime.Now;
                }
                if (!connected)
                {
                    throw new TimeoutException("Did not connect");

                }
            }

            void waitForDisconnected(TimeSpan timeout)
            {
                DateTime expire = DateTime.Now + timeout;
                TimeSpan left = timeout;
                while (connected && left.TotalMilliseconds > 0)
                {
                    lock (sync)
                    {
                        Monitor.TryEnter(sync, left);
                    }
                    left = expire - DateTime.Now;
                }
                if (connected)
                {
                    throw new TimeoutException("Did not disconnect");
                }
            }
        }

    }
}
