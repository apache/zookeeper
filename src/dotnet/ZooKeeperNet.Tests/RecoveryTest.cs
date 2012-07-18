/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
using System;
using System.Threading;
using log4net;
using NUnit.Framework;
using Org.Apache.Zookeeper.Data;

namespace ZooKeeperNet.Tests
{
    public class RecoveryTest : AbstractZooKeeperTests, IWatcher
    {
        private readonly ILog LOG = LogManager.GetLogger(typeof(RecoveryTest));
        private int setDataCount;
        private int processCount;
        private readonly string testPath = "/unittests/recoverytests/" + Guid.NewGuid();

        [Test, Explicit]
        public void ReconnectsWhenDisconnected()
        {
            using (CancellationTokenSource token = new CancellationTokenSource())
            {
                Thread thread = new Thread(Run)
                    {
                        IsBackground = true,
                        Name = "RecoveryTest.Run"
                    };
                thread.Start(token.Token);
                Thread.Sleep(15*1000);
                LOG.Error("STOP ZK!!!! Count: " + processCount);
                Thread.Sleep(20*1000);
                LOG.Error("START ZK!!! Count: " + processCount);
                Thread.Sleep(30*1000);
                LOG.Error("Stopping ZK client.");
                token.Cancel();
                LOG.Error("Waiting for thread to stop..." + processCount);
                thread.Join();
                if (thread.IsAlive)
                    Assert.Fail("Thread still alive");
                Assert.AreEqual(setDataCount, processCount, "setDataCount == processCount");
                LOG.Error("Finished:" + setDataCount + ":" + processCount);
            }
        }

        private void Run(object sender)
        {
            try
            {
                CancellationToken token = (CancellationToken)sender;
                using (ZooKeeper zooKeeper = CreateClient(this))
                {
                    Stat stat = new Stat();
                    if (zooKeeper.Exists("/unittests/recoverytests", false) == null)
                    {
                        zooKeeper.Create("/unittests", new byte[] {0}, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                        zooKeeper.Create("/unittests/recoverytests", new byte[] {0}, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                    }
                    if (zooKeeper.Exists(testPath, false) == null)
                    {   
                        zooKeeper.Create(testPath, new byte[] {0}, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                    }
                    while (zooKeeper.State.IsAlive() && !token.IsCancellationRequested)
                    {
                        try
                        {
                            zooKeeper.GetData(testPath, true, stat);
                            zooKeeper.SetData(testPath, Guid.NewGuid().ToByteArray(), -1);
                            setDataCount++;
                        }
                        catch(KeeperException ke)
                        {
                            LOG.Error(ke);
                        }
                    }
                    LOG.Error("Waiting for dispose.");
                }
            }
            catch(Exception ex)
            {
                LOG.Error(ex);
            }
        }

        public void Process(WatchedEvent @event)
        {
            LOG.Debug(@event);
            if (@event.Type == EventType.NodeCreated || @event.Type == EventType.NodeDataChanged)
            {
                Interlocked.Increment(ref processCount);
            }
        }
    }
}