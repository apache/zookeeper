namespace SharpKeeper.Tests
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Runtime.CompilerServices;
    using System.Text;
    using System.Threading;
    using NUnit.Framework;
    using Org.Apache.Zookeeper.Data;

    [TestFixture]
    public class ClientTests : AbstractZooKeeperTests
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientTests));

        /** Verify that pings are sent, keeping the "idle" client alive */
        [Test]
        public void testPing()
        {
            ZooKeeper zkIdle = null;
            ZooKeeper zkWatchCreator = null;
            try
            {
                CountdownWatcher watcher = new CountdownWatcher();
                zkIdle = CreateClient();

                zkWatchCreator = CreateClient(watcher);

                for (int i = 0; i < 10; i++)
                {
                    zkWatchCreator.Create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                }
                for (int i = 0; i < 10; i++)
                {
                    zkIdle.Exists("/" + i, true);
                }
                for (int i = 0; i < 10; i++)
                {
                    Thread.Sleep(1000);
                    zkWatchCreator.Delete("/" + i, -1);
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

        [Test]
        public void testClientwithoutWatcherObj()
        {
            performClientTest(false);
        }

        [Test]
        public void testClientWithWatcherObj()
        {
            performClientTest(true);
        }

        [Test]
        public void testACLs()
        {
            ZooKeeper zk = null;
            try
            {
                zk = CreateClient();
                try
                {
                    zk.Create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
                    Assert.Fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e)
                {
                    LOG.Info("Test successful, invalid acl received : "
                            + e.getMessage());
                }
                try
                {
                    List<ACL> testACL = new List<ACL>();
                    testACL.Add(new ACL(Perms.ALL | Perms.ADMIN, Ids.AUTH_IDS));
                    testACL.Add(new ACL(Perms.ALL | Perms.ADMIN, new ZKId("ip", "127.0.0.1/8")));
                    zk.Create("/acltest", new byte[0], testACL, CreateMode.Persistent);
                    Assert.Fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e)
                {
                    LOG.Info("Test successful, invalid acl received : "
                            + e.getMessage());
                }
                zk.AddAuthInfo("digest", "ben:passwd".getBytes());
                zk.Create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.Persistent);
                zk.Dispose();
                zk = CreateClient();
                zk.AddAuthInfo("digest", "ben:passwd2".getBytes());
                try
                {
                    zk.GetData("/acltest", false, new Stat());
                    Assert.Fail("Should have received a permission error");
                }
                catch (KeeperException e)
                {
                    Assert.AreEqual(KeeperException.Code.NOAUTH, e.GetCode());
                }
                zk.AddAuthInfo("digest", "ben:passwd".getBytes());
                zk.GetData("/acltest", false, new Stat());
                zk.SetACL("/acltest", Ids.OPEN_ACL_UNSAFE, -1);
                zk.Dispose();
                zk = CreateClient();
                zk.GetData("/acltest", false, new Stat());
                List<ACL> acls = zk.GetACL("/acltest", new Stat());
                Assert.AreEqual(1, acls.Count);
                Assert.AreEqual(Ids.OPEN_ACL_UNSAFE, acls);
                zk.Dispose();
            }
            finally
            {
                if (zk != null)
                {
                    zk.Dispose();
                }
            }
        }

        private class MyWatcher : CountdownWatcher
        {
            internal BlockingQueue<WatchedEvent> events = new BlockingQueue<WatchedEvent>(100);

            public override void Process(WatchedEvent @event)
            {
                base.Process(@event);
                if (@event.Type != EventType.None)
                {
                    try
                    {
                        events.TryEnqueue(@event, TimeSpan.FromMilliseconds(10000d));
                    }
                    catch (ThreadInterruptedException e)
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
        [Test]
        public void testMutipleWatcherObjs()
        {
            ZooKeeper zk = CreateClient(new CountdownWatcher());
            try
            {
                MyWatcher[] watchers = new MyWatcher[100];
                MyWatcher[] watchers2 = new MyWatcher[watchers.Length];
                for (int i = 0; i < watchers.Length; i++)
                {
                    watchers[i] = new MyWatcher();
                    watchers2[i] = new MyWatcher();
                    zk.Create("/foo-" + i, ("foodata" + i).getBytes(),
                            Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                }
                Stat stat = new Stat();

                //
                // test get/Exists with single set of watchers
                //   get all, then Exists all
                //
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.NotNull(zk.GetData("/foo-" + i, watchers[i], stat));
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    Assert.NotNull(zk.Exists("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData("/foo-" + i, ("foodata2-" + i).getBytes(), -1);
                    zk.SetData("/foo-" + i, ("foodata3-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event = watchers[i].events.TryDequeue(TimeSpan.FromSeconds(3d));
                    Assert.AreEqual("/foo-" + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.Type);
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
                    Assert.NotNull(zk.GetData("/foo-" + i, watchers[i], stat));
                    Assert.NotNull(zk.Exists("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData("/foo-" + i, ("foodata4-" + i).getBytes(), -1);
                    zk.SetData("/foo-" + i, ("foodata5-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event =
                        watchers[i].events.TryDequeue(TimeSpan.FromSeconds(10d));
                    Assert.AreEqual("/foo-" + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.Type);
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
                    Assert.NotNull(zk.GetData("/foo-" + i, watchers[i], stat));
                    Assert.NotNull(zk.Exists("/foo-" + i, watchers2[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++)
                {
                    zk.SetData("/foo-" + i, ("foodata6-" + i).getBytes(), -1);
                    zk.SetData("/foo-" + i, ("foodata7-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++)
                {
                    WatchedEvent @event =
                        watchers[i].events.TryDequeue(TimeSpan.FromSeconds(3000));
                    Assert.AreEqual("/foo-" + i, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.Type);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.AreEqual(0, watchers[i].events.Count);

                    // watchers2
                    WatchedEvent event2 =
                        watchers2[i].events.TryDequeue(TimeSpan.FromSeconds(3000));
                    Assert.AreEqual("/foo-" + i, event2.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, event2.Type);
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
            ZooKeeper zk = null;
            try
            {
                MyWatcher watcher = new MyWatcher();
                zk = CreateClient(watcher);
                LOG.Info("Before Create /benwashere");
                zk.Create("/benwashere", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                LOG.Info("After Create /benwashere");
                try
                {
                    zk.SetData("/benwashere", "hi".getBytes(), 57);
                    Assert.Fail("Should have gotten BadVersion exception");
                }
                catch (KeeperException.BadVersionException e)
                {
                    // expected that
                }
                catch (KeeperException e)
                {
                    Assert.Fail("Should have gotten BadVersion exception");
                }
                LOG.Info("Before Delete /benwashere");
                zk.Delete("/benwashere", 0);
                LOG.Info("After Delete /benwashere");
                zk.Dispose();
                //LOG.Info("Closed client: " + zk.describeCNXN());
                Thread.Sleep(2000);

                zk = CreateClient(watcher);
                //LOG.Info("Created a new client: " + zk.describeCNXN());
                LOG.Info("Before Delete /");

                try
                {
                    zk.Delete("/", -1);
                    Assert.Fail("Deleted root!");
                }
                catch (KeeperException.BadArgumentsException e)
                {
                    // good, expected that
                }
                Stat stat = new Stat();
                // Test basic Create, ls, and GetData
                zk.Create("/pat", "Pat was here".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                LOG.Info("Before Create /ben");
                zk.Create("/pat/ben", "Ben was here".getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                LOG.Info("Before GetChildren /pat");
                List<String> children = zk.GetChildren("/pat", false);
                Assert.AreEqual(1, children.Count);
                Assert.AreEqual("ben", children[0]);
                List<String> children2 = zk.GetChildren("/pat", false, null);
                Assert.AreEqual(children, children2);
                String value = Encoding.UTF8.GetString(zk.GetData("/pat/ben", false, stat));
                Assert.AreEqual("Ben was here", value);
                // Test stat and watch of non existent node

                try
                {
                    if (withWatcherObj)
                    {
                        Assert.AreEqual(null, zk.Exists("/frog", watcher));
                    }
                    else
                    {
                        Assert.AreEqual(null, zk.Exists("/frog", true));
                    }
                    LOG.Info("Comment: asseting passed for frog setting /");
                }
                catch (KeeperException.NoNodeException e)
                {
                    // OK, expected that
                }
                zk.Create("/frog", "hi".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                // the first poll is just a session delivery
                LOG.Info("Comment: checking for events Length "
                         + watcher.events.Count);
                WatchedEvent @event = watcher.events.TryDequeue(TimeSpan.FromSeconds(3000));
                Assert.AreEqual("/frog", @event.Path);
                Assert.AreEqual(EventType.NodeCreated, @event.Type);
                Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                // Test child watch and Create with sequence
                zk.GetChildren("/pat/ben", true);
                for (int i = 0; i < 10; i++)
                {
                    zk.Create("/pat/ben/" + i + "-", Convert.ToString(i).getBytes(),
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                }
                children = zk.GetChildren("/pat/ben", false);

                children = children.OrderBy(s => s).ToList();

                Assert.AreEqual(10, children.Count);
                for (int i = 0; i < 10; i++)
                {
                    String name = children[i];
                    Assert.True(name.StartsWith(i + "-"), "starts with -");
                    byte[] b;
                    if (withWatcherObj)
                    {
                        b = zk.GetData("/pat/ben/" + name, watcher, stat);
                    }
                    else
                    {
                        b = zk.GetData("/pat/ben/" + name, true, stat);
                    }
                    Assert.AreEqual(Convert.ToString(i), Encoding.UTF8.GetString(b));
                    zk.SetData("/pat/ben/" + name, "new".getBytes(),
                            stat.Version);
                    if (withWatcherObj)
                    {
                        stat = zk.Exists("/pat/ben/" + name, watcher);
                    }
                    else
                    {
                        stat = zk.Exists("/pat/ben/" + name, true);
                    }
                    zk.Delete("/pat/ben/" + name, stat.Version);
                }
                @event = watcher.events.TryDequeue(TimeSpan.FromSeconds(3000));
                Assert.AreEqual("/pat/ben", @event.Path);
                Assert.AreEqual(EventType.NodeChildrenChanged, @event.Type);
                Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                for (int i = 0; i < 10; i++)
                {
                    @event = watcher.events.TryDequeue(TimeSpan.FromSeconds(3000));
                    String name = children[i];
                    Assert.AreEqual("/pat/ben/" + name, @event.Path);
                    Assert.AreEqual(EventType.NodeDataChanged, @event.Type);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                    @event = watcher.events.TryDequeue(TimeSpan.FromSeconds(3000));
                    Assert.AreEqual("/pat/ben/" + name, @event.Path);
                    Assert.AreEqual(EventType.NodeDeleted, @event.Type);
                    Assert.AreEqual(KeeperState.SyncConnected, @event.State);
                }
                zk.Create("/good\u0040path", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);

                zk.Create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                try
                {
                    zk.Create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                            CreateMode.Persistent);
                    Assert.Fail("duplicate Create allowed");
                }
                catch (KeeperException.NodeExistsException e)
                {
                    // OK, expected that
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

        // Test that sequential filenames are being Created correctly,
        // with 0-pAdding in the filename
        [Test]
        public void testSequentialNodeNames()
        {
            String path = "/SEQUENCE";
            String file = "TEST";
            String filepath = path + "/" + file;

            ZooKeeper zk = null;
            try
            {
                zk = CreateClient();
                zk.Create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                List<String> children = zk.GetChildren(path, false);
                Assert.AreEqual(1, children.Count);
                Assert.AreEqual(file + "0000000000", children[0]);

                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);
                children = zk.GetChildren(path, false);
                Assert.AreEqual(2, children.Count);
                Assert.True(children.Contains(file + "0000000001"), "contains child 1");

                zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);
                children = zk.GetChildren(path, false);
                Assert.AreEqual(3, children.Count);
                Assert.True(children.Contains(file + "0000000002"), "contains child 2");

                // The pattern is holding so far.  Let's run the counter a bit
                // to be sure it continues to spit out the correct answer
                for (int i = children.Count; i < 105; i++)
                    zk.Create(filepath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EphemeralSequential);

                children = zk.GetChildren(path, false);
                Assert.True(children.Contains(file + "0000000104"), "contains child 104");

            }
            finally
            {
                if (zk != null)
                    zk.Dispose();
            }
        }

        // Test that data provided when 
        // creating sequential nodes is stored properly
        [Test]
        public void testSequentialNodeData()
        {
            ZooKeeper zk = null;
            String queue_handle = "/queue";
            try
            {
                zk = CreateClient();

                zk.Create(queue_handle, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                zk.Create(queue_handle + "/element", "0".getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                zk.Create(queue_handle + "/element", "1".getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PersistentSequential);
                List<String> children = zk.GetChildren(queue_handle, true);
                Assert.AreEqual(children.Count, 2);
                String child1 = children[0];
                String child2 = children[1];
                int compareResult = child1.CompareTo(child2);
                Assert.AreNotSame(compareResult, 0);
                if (compareResult < 0)
                {
                }
                else
                {
                    String temp = child1;
                    child1 = child2;
                    child2 = temp;
                }
                String child1data = Encoding.UTF8.GetString(zk.GetData(queue_handle + "/" + child1, false, null));
                String child2data = Encoding.UTF8.GetString(zk.GetData(queue_handle + "/" + child2, false, null));
                Assert.AreEqual(child1data, "0");
                Assert.AreEqual(child2data, "1");
            }
            finally
            {
                if (zk != null)
                {
                    zk.Dispose();
                }
            }

        }


        private void verifyCreateFails(String path, ZooKeeper zk)
        {
            try
            {
                zk.Create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            }
            catch (InvalidOperationException e)
            {
                // this is good
                return;
            }
            Assert.Fail("bad path \"" + path + "\" not caught");
        }

        // Test that the path string is validated
        [Test]
        public void testPathValidation()
        {
            ZooKeeper zk = CreateClient();

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

            zk.Create("/Createseqpar", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.Persistent);
            // next two steps - related to sequential processing
            // 1) verify that empty child name Assert.Fails if not sequential
            try
            {
                zk.Create("/Createseqpar/", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.Persistent);
                Assert.True(false);
            }
            catch (InvalidOperationException be)
            {
                // catch this.
            }

            // 2) verify that empty child name success if sequential 
            zk.Create("/Createseqpar/", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PersistentSequential);
            zk.Create("/Createseqpar/.", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PersistentSequential);
            zk.Create("/Createseqpar/..", null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PersistentSequential);
            try
            {
                zk.Create("/Createseqpar//", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                Assert.True(false);
            }
            catch (InvalidOperationException be)
            {
                // catch this.
            }
            try
            {
                zk.Create("/Createseqpar/./", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                Assert.True(false);
            }
            catch (InvalidOperationException be)
            {
                // catch this.
            }
            try
            {
                zk.Create("/Createseqpar/../", null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PersistentSequential);
                Assert.True(false);
            }
            catch (InvalidOperationException be)
            {
                // catch this.
            }


            //check for the code path that throws at server
            //PrepRequestProcessor.setFailCreate(true);
            try
            {
                zk.Create("/m", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
                Assert.True(false);
            }
            catch (KeeperException.BadArgumentsException be)
            {
                // catch this.
            }
            //PrepRequestProcessor.setFailCreate(false);
            zk.Create("/.foo", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/.f.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/..f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/..f..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f.c", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f\u0040f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/.f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/f.", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/..f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/f..", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/.f/f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/f/f./f", null, Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
        }


        [Test]
        public void testDeleteWithChildren()
        {
            ZooKeeper zk = CreateClient();
            zk.Create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            zk.Create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            try
            {
                zk.Delete("/parent", -1);
                Assert.Fail("Should have received a not equals message");
            }
            catch (KeeperException e)
            {
                Assert.AreEqual(KeeperException.Code.NOTEMPTY, e.GetCode());
            }
            zk.Delete("/parent/child", -1);
            zk.Delete("/parent", -1);
            zk.Dispose();
        }

    }

    public class CountdownWatcher : IWatcher
    {
        // XXX this doesn't need to be volatile! (Should probably be final)

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
