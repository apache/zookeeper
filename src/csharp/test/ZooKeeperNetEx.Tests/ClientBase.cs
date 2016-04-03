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
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
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

        protected const int CONNECTION_TIMEOUT = 4000;
        private readonly string m_currentRoot;

        private const string hostPort = "127.0.0.1,localhost";
        internal const string testsNode = "/tests";

        private readonly ConcurrentBag<ZooKeeper> allClients = new ConcurrentBag<ZooKeeper>();
        
        protected Task<ZooKeeper> createClient(string chroot = null, int timeout = CONNECTION_TIMEOUT)
        {
            return createClient(NullWatcher.Instance, chroot, timeout);
        }

        protected async Task<ZooKeeper> createClient(Watcher watcher, string chroot=null, int timeout = CONNECTION_TIMEOUT)
        {
            if (watcher == null) watcher = NullWatcher.Instance;
            var zk = new ZooKeeper(hostPort + m_currentRoot + chroot, timeout, watcher);
            allClients.Add(zk);
            if (!await zk.connectedTask.WaitAsync().WithTimeout(timeout)) {
                Assert.fail("Unable to connect to server");
            }

            return zk;
        }


        protected ClientBase()
        {
            m_currentRoot = createNode(testsNode + "/", CreateMode.PERSISTENT_SEQUENTIAL).Result;
        }


        public void Dispose()
        {
            deleteNode(m_currentRoot).Wait();
            Task.WaitAll(allClients.Select(c => c.closeAsync()).ToArray());
        }

        internal static Task deleteNode(string path)
        {
            return ZooKeeper.Using(hostPort, CONNECTION_TIMEOUT, NullWatcher.Instance, zk =>
            {
                return ZKUtil.deleteRecursiveAsync(zk, path);
            });
        }

        internal static Task<string> createNode(string path, CreateMode createMode)
        {
            return ZooKeeper.Using(hostPort, CONNECTION_TIMEOUT, NullWatcher.Instance, async zk =>
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
        private class NullWatcher : Watcher
        {
            public static readonly NullWatcher Instance = new NullWatcher();
            private NullWatcher() { }
            public override Task process(WatchedEvent @event)
            {
                return CompletedTask;
                // nada
            }
        }
    }
}