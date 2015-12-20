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

using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using org.apache.utils;
using Xunit;

namespace org.apache.zookeeper {
    [Collection("Setup")]
    public abstract class ClientBase:IDisposable {

        protected static class Arrays {
            public static List<T> asList<T>(params T[]objs) {
                return new List<T>(objs);
            }
        }

        protected const int CONNECTION_TIMEOUT = 10000;
        private string m_currentRoot { get; set; }

        private const string hostPort = "127.0.0.1";
        internal const string testsNode = "/tests";

        private static readonly ILogProducer LOG = TypeLogger<ClientBase>.Instance;
        private LinkedList<TestableZooKeeper> allClients;
        private bool allClientsSetup;
        
        protected TestableZooKeeper createClient() {
            return createClient(new CountdownWatcher(), hostPort + m_currentRoot, CONNECTION_TIMEOUT);
        }

        protected ZooKeeper createClient(CountdownWatcher watcher, int timeout)
        {
            return createClient(watcher, hostPort + m_currentRoot, timeout);
        }

        protected TestableZooKeeper createClient(string chroot) {
            return createClient(new CountdownWatcher(), hostPort + m_currentRoot + chroot, CONNECTION_TIMEOUT);
        }

        protected TestableZooKeeper createClient(CountdownWatcher watcher) {
            return createClient(watcher, hostPort + m_currentRoot, CONNECTION_TIMEOUT);
        }

        private TestableZooKeeper createClient(CountdownWatcher watcher, string hp, int timeout) {
            watcher.reset();
            var zk = new TestableZooKeeper(hp, timeout, watcher);
            if (!watcher.clientConnected.Wait(timeout)) {
                Assert.fail("Unable to connect to server");
            }
            lock (this) {
                if (!allClientsSetup) {
                    LOG.error("allClients never setup");
                    Assert.fail("allClients never setup");
                }
                if (allClients != null) {
                    allClients.AddLast(zk);
                }
                else {
                    // test done - close the zk, not needed
                    zk.close();
                }
            }

            return zk;
        }


        public ClientBase()
        {
            m_currentRoot = createNode(testsNode + "/", CreateMode.PERSISTENT_SEQUENTIAL).GetAwaiter().GetResult();
            lock (this)
            {
                allClients = new LinkedList<TestableZooKeeper>();
                allClientsSetup = true;
            }
        }


        public virtual void Dispose() {
            deleteNode(m_currentRoot).GetAwaiter().GetResult();
            lock (this)
            {
                if (allClients != null)
                {
                    foreach (var zk in allClients)
                    {
                        if (zk != null)
                        {
                            zk.close();
                        }
                    }
                }
                allClients = null;
            }
        }

        internal static Task deleteNode(string path)
        {
            return ZooKeeper.Using(hostPort, 10000, new NullWatcher(), async zk =>
            {
                await ZKUtil.deleteRecursiveAsync(zk, path);
                await zk.sync("/");
            });
        }

        internal static Task<string> createNode(string path, CreateMode createMode)
        {
            return ZooKeeper.Using(hostPort, 10000, new NullWatcher(), async zk =>
            {
                string newNode = await zk.createAsync(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, createMode);
                await zk.sync("/");
                return newNode;
            });
        }

        /// <summary>
        ///     In general don't use this. Only use in the special case that you
        ///     want to ignore results (for whatever reason) in your test. Don't
        ///     use empty watchers in real code!
        /// </summary>
        protected class NullWatcher : Watcher
        {
            public override Task process(WatchedEvent @event)
            {
                return CompletedTask;
                // nada
            }
        }
        
        protected class CountdownWatcher : Watcher {
            internal ManualResetEventSlim clientConnected;

            public CountdownWatcher() {
                reset();
            }

            public void reset() {
                lock (this) {
                    clientConnected = new ManualResetEventSlim(false);
                }
            }

            public override Task process(WatchedEvent @event) {
                lock (this) {
                    if (@event.getState() == Event.KeeperState.SyncConnected ||
                        @event.getState() == Event.KeeperState.ConnectedReadOnly) {
                        Monitor.PulseAll(this);
                        clientConnected.Set();
                    }
                    else {
                        Monitor.PulseAll(this);
                    }
                }
                return CompletedTask;
            }
        }
    }
}