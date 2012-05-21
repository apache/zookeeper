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
﻿namespace ZooKeeperNet.Tests
{
    using System;
    using System.Collections.Concurrent;
    using System.Collections.Generic;
    using System.Linq;
    using System.Text;
    using System.Threading;
    using log4net;
    using Org.Apache.Zookeeper.Data;
    using Microsoft.VisualStudio.TestTools.UnitTesting;

    [TestClass]
    public class ClientTests : AbstractZooKeeperTests
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ClientTests));

        [TestMethod]
        public void ArrayResize()
        {
            byte[] buffer = new byte[4];
            Array.Resize(ref buffer, 1024 * 1024);
            //Array.Clear(buffer, 0, buffer.Length);
            Array.Resize(ref buffer, 2048 * 1024);
            //Array.Clear(buffer, 0, buffer.Length);
            Array.Resize(ref buffer, 3072 * 1024);
            //Array.Clear(buffer, 0, buffer.Length);
            Array.Resize(ref buffer, 4096 * 1024);
        }

        [TestMethod]
        public void ArrayNew()
        {
            int x = 0;
            Assert.AreEqual(Interlocked.Increment(ref x), 1);
            byte[] buffer = new byte[4];
            buffer = new byte[1024 * 1024];
            buffer = new byte[2048 * 1024];
            buffer = new byte[3072 * 1024];
            buffer = new byte[4096 * 1024];
        }

        [TestMethod]
        public void DeleteAllNodeExceptPraweda()
        {
            using (var zk = CreateClient())
            {
                DeleteChild(zk, "/");
            }
        }

        private void DeleteChild(ZooKeeper zk, string path)
        {
            if (!string.IsNullOrEmpty(path) && !path.Contains("praweda") && !path.Contains("zookeeper"))
            {
                var lstChild = zk.GetChildren(path, false);
                foreach (var child in lstChild)
                {
                    if(path != "/")
                        DeleteChild(zk, path + "/" + child);
                    else
                        DeleteChild(zk, "/" + child);
                }
                if (path != "/")
                    zk.Delete(path, -1);
            }
        }

        /** Verify that pings are sent, keeping the "idle" client alive */
        [TestMethod]
        public void testPing()
        {
            ZooKeeper zkIdle = null;
            ZooKeeper zkWatchCreator = null;
            try
            {
                CountdownWatcher watcher = new CountdownWatcher();
                zkIdle = CreateClient();

                zkWatchCreator = CreateClient(watcher);

                var node = Guid.NewGuid();
                for (int i = 0; i < 10; i++)
                {
                    zkWatchCreator.Create("/" + node + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                }
                for (int i = 0; i < 10; i++)
                {                      
                    zkIdle.Exists("/" + node + i, true);
                }
                for (int i = 0; i < 10; i++)
                {
                    Thread.Sleep(1000);
                    zkWatchCreator.Delete("/" + node + i, -1);
                }
                // The bug will manifest itself here because zkIdle will expire
                zkIdle.Exists("/0", false);
            }
            finally
            {
                if (zkIdle != null)
                {
                    zkIdle.Dispose();
                }
                if (zkWatchCreator != null)
                {
                    zkWatchCreator.Dispose();
                }
            }
        }

        [TestMethod]
        public void testClientWithoutWatcherObj()
        {
            performClientTest(false);
        }

        [TestMethod]
        public void testClientWithWatcherObj()
        {
            performClientTest(true);
        }

        [TestMethod]
        public void testACLs()
        {
            string name = "/" + Guid.NewGuid() + "acltest";
            using (var zk = CreateClient())
            {
                try
                {
                    zk.Create(name, new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
                    Assert.Fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e)
                {
                    LOG.Info("Test successful, invalid acl received : "
                             + e.ErrorMessage);
                }
                try
                {
                    List<ACL> testACL = new List<ACL>();
                    testACL.Add(new ACL(Perms.ALL | Perms.ADMIN, Ids.AUTH_IDS));
                    testACL.Add(new ACL(Perms.ALL | Perms.ADMIN, new ZKId("ip", "127.0.0.1/8")));
                    zk.Create(name, new byte[0], testACL, CreateMode.Persistent);
                    Assert.Fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e)
                {
                    LOG.Info("Test successful, invalid acl received : "
                             + e.ErrorMessage);
                }
                zk.AddAuthInfo("digest", "ben:passwd".GetBytes());
                zk.Create(name, new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
            }

            using (var zk = CreateClient())
            {
                zk.AddAuthInfo("digest", "ben:passwd2".GetBytes());
                try
                {
                    zk.GetData(name, false, new Stat());
                    Assert.Fail("Should have received a permission error");
                }
                catch (KeeperException e)
                {
                    Assert.AreEqual(KeeperException.Code.NOAUTH, e.ErrorCode);
                }
                zk.AddAuthInfo("digest", "ben:passwd".GetBytes());
                zk.GetData(name, false, new Stat());
                zk.SetACL(name, Ids.OPEN_ACL_UNSAFE, -1);
            }

            using (var zk = CreateClient())
            {
                zk.GetData(name, false, new Stat());
                var acls = zk.GetACL(name, new Stat());
                Assert.AreEqual(1, acls.Count());
                for(int i=0;i<Ids.OPEN_ACL_UNSAFE.Count;i++)
                    Assert.AreEqual(Ids.OPEN_ACL_UNSAFE[i], acls.ElementAt(i));
            }
        }

        private class MyWatcher : CountdownWatcher
        {
            internal readonly BlockingCollection<WatchedEvent> events = new BlockingCollection<WatchedEvent>(100);

            public override void Process(WatchedEvent @event)
            {
                base.Process(@event);
                if (@event.EventType != EventType.None)
                {
                    try
                    {
                        events.TryAdd(@event, TimeSpan.FromMilliseconds(10000));
                    }
                    catch (ThreadInterruptedException)
                    {
                        LOG.Warn("ignoring interrupt during @event.put");
                    }
                }
            }
        }

        /**
         * Register multiple watchers and verify that they all get notified and
         * in the right order.
         */
        [TestMethod]
        public void testMutipleWatcherObjs()
        {
            ZooKeeper zk = CreateClient(new CountdownWatcher());
            try
            {
                MyWatcher[] watchers = new MyWatcher[100];
                MyWatcher[] watchers2 = new MyWatcher[watchers.Length];
                string name = "/" + Guid.NewGuid() + "foo-";
                for (int i = 0; i < watchers.Length; i++)
                {
                    watchers[i] = new MyWatcher();
                    watchers2[i] = new MyWatcher();
                    zk.Create(name + i, ("foodata" + i).GetBytes(),
                            Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                }
                Stat stat = new Stat();

                //
                // test get/Exists with single set of watchers
                //   get all, then Exists all
                //
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.IsNotNull(zk.GetData(name + i, watchers[i], stat));
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.IsNotNull(zk.Exists(name + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData(name + i, ("foodata2-" + i).GetBytes(), -1);
                    zk.SetData(name + i, ("foodata3-" + i).GetBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event;
                    watchers[i].events.TryTake(out @event, TimeSpan.FromSeconds(3d));
                    Assert.AreEqual(name + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.AreEqual(0, watchers[i].events.Count);
                }

                //
                // test get/Exists with single set of watchers
                //  get/Exists together
                //
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.IsNotNull(zk.GetData(name + i, watchers[i], stat));
                    Assert.IsNotNull(zk.Exists(name + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData(name + i, ("foodata4-" + i).GetBytes(), -1);
                    zk.SetData(name + i, ("foodata5-" + i).GetBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event;
                    watchers[i].events.TryTake(out @event, TimeSpan.FromSeconds(10d));
                    Assert.AreEqual(name + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.AreEqual(0, watchers[i].events.Count);
                }

                //
                // test get/Exists with two sets of watchers
                //
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.IsNotNull(zk.GetData(name + i, watchers[i], stat));
                    Assert.IsNotNull(zk.Exists(name + i, watchers2[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData(name + i, ("foodata6-" + i).GetBytes(), -1);
                    zk.SetData(name + i, ("foodata7-" + i).GetBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event;
                    watchers[i].events.TryTake(out @event, TimeSpan.FromSeconds(3000));
                    Assert.AreEqual(name + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.AreEqual(0, watchers[i].events.Count);

                    // watchers2
                    WatchedEvent event2;
                    watchers2[i].events.TryTake(out @event2, TimeSpan.FromSeconds(3000));
                    Assert.AreEqual(name + i, event2.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, event2.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, event2.State);

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.AreEqual(0, watchers2[i].events.Count);
                }

            }
            finally
            {
                if (zk != null)
                {
                    zk.Dispose();
                }
            }
        }

        private void performClientTest(bool withWatcherObj)
        {
            MyWatcher watcher = new MyWatcher();
            using (var zk = CreateClient(watcher))
            {
                LOG.Info("Before Create /benwashere");
                string benwashere = "/" + Guid.NewGuid() + "benwashere";
                zk.Create(benwashere, "".GetBytes(), Ids.OPEN_ACL_UNSAFE,
                          CreateMode.Persistent);
                LOG.Info("After Create /benwashere");
                try
                {
                    zk.SetData(benwashere, "hi".GetBytes(), 57);
                    Assert.Fail("Should have gotten BadVersion exception");
                }
                catch (KeeperException.BadVersionException)
                {
                    // expected that
                }
                catch (KeeperException)
                {
                    Assert.Fail("Should have gotten BadVersion exception");
                }
                LOG.Info("Before Delete /benwashere");
                zk.Delete(benwashere, 0);
                LOG.Info("After Delete /benwashere");
            }

            //LOG.Info("Closed client: " + zk.describeCNXN());
            Thread.Sleep(2000);

            using (var zk = CreateClient(watcher))
            {
                //LOG.Info("Created a new client: " + zk.describeCNXN());
                LOG.Info("Before Delete /");
                try
                {
                    zk.Delete("/", -1);
                    Assert.Fail("Deleted root!");
                }
                catch (KeeperException.BadArgumentsException)
                {
                    // good, expected that
                }
                Stat stat = new Stat();
                // Test basic Create, ls, and GetData
                string pat = "/pat" + Guid.NewGuid();
                string patPlusBen = pat + "/ben";
                zk.Create(pat, "Pat was here".GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                LOG.Info("Before Create /ben");
                zk.Create(patPlusBen, "Ben was here".GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                LOG.Info("Before GetChildren /pat");
                var children = zk.GetChildren(pat, false);
                Assert.AreEqual(1, children.Count());
                Assert.AreEqual("ben", children.ElementAt(0));
                var children2 = zk.GetChildren(pat, false, null);
                for(int i=0;i<children.Count();i++)
                    Assert.AreEqual(children.ElementAt(i), children2.ElementAt(i));

                string value = Encoding.UTF8.GetString(zk.GetData(patPlusBen, false, stat));
                Assert.AreEqual("Ben was here", value);
                // Test stat and watch of non existent node

                string frog = "/frog" + Guid.NewGuid();
                try
                {
                    if (withWatcherObj)
                    {
                        Assert.AreEqual(null, zk.Exists(frog, watcher));
                    }
                    else
                    {
                        Assert.AreEqual(null, zk.Exists(frog, true));
                    }
                    LOG.Info("Comment: asseting passed for frog setting /");
                }
                catch (KeeperException.NoNodeException)
                {
                    // OK, expected that
                }
                zk.Create(frog, "hi".GetBytes(), Ids.OPEN_ACL_UNSAFE,
                          CreateMode.Persistent);
                // the first poll is just a session delivery
                LOG.Info("Comment: checking for events Length "
                         + watcher.events.Count);
                WatchedEvent @event;
                watcher.events.TryTake(out @event, TimeSpan.FromSeconds(3000));
                Assert.AreEqual(frog, @event.Path);
                Assert.AreEqual(EventType.NodeCreated, @event.EventType);
                Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                // Test child watch and Create with sequence
                zk.GetChildren(patPlusBen, true);
                for (int i = 0; i < 10; i++)
                {
                    zk.Create(patPlusBen + "/" + i + "-", Convert.ToString(i).GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                }
                children = zk.GetChildren(patPlusBen, false);

                children = children.OrderBy(s => s).ToList();

                Assert.AreEqual(10, children.Count());
                for (int i = 0; i < 10; i++)
                {
                    string name = children.ElementAt(i);
                    Assert.IsTrue(name.StartsWith(i + "-"), "starts with -");
                    byte[] b;
                    if (withWatcherObj)
                    {
                        b = zk.GetData(patPlusBen + "/" + name, watcher, stat);
                    }
                    else
                    {
                        b = zk.GetData(patPlusBen + "/" + name, true, stat);
                    }
                    Assert.AreEqual(Convert.ToString(i), Encoding.UTF8.GetString(b));
                    zk.SetData(patPlusBen + "/" + name, "new".GetBytes(),
                               stat.Version);
                    if (withWatcherObj)
                    {
                        stat = zk.Exists(patPlusBen + "/" + name, watcher);
                    }
                    else
                    {
                        stat = zk.Exists(patPlusBen + "/" + name, true);
                    }
                    zk.Delete(patPlusBen + "/" + name, stat.Version);
                }
                
                watcher.events.TryTake(out @event, TimeSpan.FromSeconds(3));
                Assert.AreEqual(patPlusBen, @event.Path);
                Assert.AreEqual(EventType.NodeChildrenChanged, @event.EventType);
                Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                for (int i = 0; i < 10; i++)
                {
                    watcher.events.TryTake(out @event, TimeSpan.FromSeconds(3));
                    string name = children.ElementAt(i);
                    Assert.AreEqual(patPlusBen + "/" + name, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                    watcher.events.TryTake(out @event, TimeSpan.FromSeconds(3));
                    Assert.AreEqual(patPlusBen + "/" + name, @event.Path);
                    Assert.AreEqual(EventType.NodeDeleted, @event.EventType);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                }
                zk.Create("/good" + Guid.NewGuid() + "\u0040path", "".GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);

                var dup = "/duplicate" + Guid.NewGuid();
                zk.Create(dup, "".GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                try
                {
                    zk.Create(dup, "".GetBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                    Assert.Fail("duplicate Create allowed");
                }
                catch (KeeperException.NodeExistsException)
                {
                    // OK, expected that
                }
            }
        }

        // Test that sequential filenames are being Created correctly,
        // with 0-pAdding in the filename
        [TestMethod]
        public void testSequentialNodeNames()
        {
            string path = "/SEQUENCE" + Guid.NewGuid();
            string file = "TEST";
            string filepath = path + "/" + file;

            using (ZooKeeper zk = CreateClient())
            {
                zk.Create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                var children = zk.GetChildren(path, false);
                Assert.AreEqual(1, children.Count());
                Assert.AreEqual(file + "0000000000", children.ElementAt(0));

                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);
                children = zk.GetChildren(path, false);
                Assert.AreEqual(2, children.Count());
                Assert.IsTrue(children.Contains(file + "0000000001"), "contains child 1");

                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);
                children = zk.GetChildren(path, false);
                Assert.AreEqual(3, children.Count());
                Assert.IsTrue(children.Contains(file + "0000000002"), "contains child 2");

                // The pattern is holding so far.  Let's run the counter a bit
                // to be sure it continues to spit out the correct answer
                for (int i = children.Count(); i < 105; i++)
                    zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);

                children = zk.GetChildren(path, false);
                Assert.IsTrue(children.Contains(file + "0000000104"), "contains child 104");

            }
        }

        // Test that data provided when 
        // creating sequential nodes is stored properly
        [TestMethod]
        public void testSequentialNodeData()
        {
            string queue_handle = "/queue" + Guid.NewGuid();
            using (ZooKeeper zk = CreateClient())
            {

                zk.Create(queue_handle, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(queue_handle + "/element", "0".GetBytes(), Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PersistentSequential);
                zk.Create(queue_handle + "/element", "1".GetBytes(), Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PersistentSequential);
                var children = zk.GetChildren(queue_handle, true);
                Assert.AreEqual(children.Count(), 2);
                string child1 = children.ElementAt(0);
                string child2 = children.ElementAt(1);
                int compareResult = child1.CompareTo(child2);
                Assert.AreNotSame(compareResult, 0);
                if (compareResult < 0)
                {
                }
                else
                {
                    string temp = child1;
                    child1 = child2;
                    child2 = temp;
                }
                string child1data = Encoding.UTF8.GetString(zk.GetData(queue_handle + "/" + child1, false, null));
                string child2data = Encoding.UTF8.GetString(zk.GetData(queue_handle + "/" + child2, false, null));
                Assert.AreEqual(child1data, "0");
                Assert.AreEqual(child2data, "1");
            }
        }


        private void verifyCreateFails(string path, ZooKeeper zk)
        {
            try
            {
                zk.Create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            }
            catch (InvalidOperationException)
            {
                // this is good
                return;
            }
            Assert.Fail("bad path \"" + path + "\" not caught");
        }

        // Test that the path string is validated
        [TestMethod]
        public void testPathValidation()
        {
            using (ZooKeeper zk = CreateClient())
            {
                verifyCreateFails(null, zk);
                verifyCreateFails("", zk);
                verifyCreateFails("//", zk);
                verifyCreateFails("///", zk);
                verifyCreateFails("////", zk);
                verifyCreateFails("/.", zk);
                verifyCreateFails("/..", zk);
                verifyCreateFails("/./", zk);
                verifyCreateFails("/../", zk);
                verifyCreateFails("/foo/./", zk);
                verifyCreateFails("/foo/../", zk);
                verifyCreateFails("/foo/.", zk);
                verifyCreateFails("/foo/..", zk);
                verifyCreateFails("/./.", zk);
                verifyCreateFails("/../..", zk);
                verifyCreateFails("/\u0001foo", zk);
                verifyCreateFails("/foo/bar/", zk);
                verifyCreateFails("/foo//bar", zk);
                verifyCreateFails("/foo/bar//", zk);

                verifyCreateFails("foo", zk);
                verifyCreateFails("a", zk);

                string createseqpar = "/Createseqpar" + Guid.NewGuid();
                zk.Create(createseqpar, null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                // next two steps - related to sequential processing
                // 1) verify that empty child name Assert.Fails if not sequential
                try
                {
                    zk.Create(createseqpar, null, Ids.OPEN_ACL_UNSAFE,
                            CreateMode.Persistent);
                    Assert.IsTrue(false);
                }
                catch (Exception)
                {
                    // catch this.
                }

                // 2) verify that empty child name success if sequential 
                zk.Create(createseqpar, null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                zk.Create(createseqpar + "/.", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                zk.Create(createseqpar + "/..", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                try
                {
                    zk.Create(createseqpar + "//", null, Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PersistentSequential);
                    Assert.IsTrue(false);
                }
                catch (InvalidOperationException)
                {
                    // catch this.
                }
                try
                {
                    zk.Create(createseqpar + "/./", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                    Assert.IsTrue(false);
                }
                catch (Exception)
                {
                    // catch this.
                }
                try
                {
                    zk.Create(createseqpar + "/../", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                    Assert.IsTrue(false);
                }
                catch (Exception)
                {
                    // catch this.
                }

                zk.Create("/.foo" + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/.f." + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/..f" + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/..f.." + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/f.c" + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/f\u0040f" + Guid.NewGuid(), null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                var f = "/f" + Guid.NewGuid();
                zk.Create(f, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/.f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/f.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/..f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/f..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/.f/f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(f + "/f./f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            }
        }


        [TestMethod]
        public void testDeleteWithChildren()
        {
            using (ZooKeeper zk = CreateClient())
            {
                zk.Create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                try
                {
                    zk.Delete("/parent", -1);
                    Assert.Fail("Should have received a not equals message");
                }
                catch (KeeperException e)
                {
                    Assert.AreEqual(KeeperException.Code.NOTEMPTY, e.ErrorCode);
                }
                zk.Delete("/parent/child", -1);
                zk.Delete("/parent", -1);
            }
        }

    }
}
