using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using org.apache.utils;
using org.apache.zookeeper.data;
using org.apache.zookeeper.proto;
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

namespace org.apache.zookeeper.test {
   
    public sealed class ClientTest : ClientBase {
        private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof (ClientTest));

        /// <summary>
        /// Verify that pings are sent, keeping the "idle" client alive </summary>
        [Fact]
        public void testPing() {
            ZooKeeper zkIdle = null;
            ZooKeeper zkWatchCreator = null;
            try {
                CountdownWatcher watcher = new CountdownWatcher();
                zkIdle = createClient(watcher, 10000);

                zkWatchCreator = createClient();

                for (int i = 0; i < 10; i++) {
                    zkWatchCreator.create("/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
                for (int i = 0; i < 10; i++) {
                    zkIdle.exists("/" + i, true);
                }
                for (int i = 0; i < 10; i++) {
                    Thread.Sleep(1000);
                    zkWatchCreator.delete("/" + i, -1);
                }
                // The bug will manifest itself here because zkIdle will expire
                zkIdle.exists("/0", false);
            }
            finally {
                if (zkIdle != null) {
                    zkIdle.close();
                }
                if (zkWatchCreator != null) {
                    zkWatchCreator.close();
                }
            }
        }



        [Fact]
        public void testClientwithoutWatcherObj() {
            performClientTest(false);
        }



        [Fact]
        public void testClientWithWatcherObj() {
            performClientTest(true);
        }
        

        [Fact]
        public void testACLs() {
            ZooKeeper zk = null;
            try {
                zk = createClient();
                try {
                    zk.create("/acltest", new byte[0], ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                    Assert.fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e) {
                    LOG.info("Test successful, invalid acl received : " + e.Message);
                }
                try {
                    List<ACL> testACL = new List<ACL>();
                    testACL.Add(new ACL((int) (ZooDefs.Perms.ALL | ZooDefs.Perms.ADMIN), ZooDefs.Ids.AUTH_IDS));
                    testACL.Add(new ACL((int) (ZooDefs.Perms.ALL | ZooDefs.Perms.ADMIN), new Id("ip", "127.0.0.1/8")));
                    zk.create("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
                    Assert.fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e) {
                    LOG.info("Test successful, invalid acl received : " + e.Message);
                }
                zk.addAuthInfo("digest", "ben:passwd".getBytes());
                zk.create("/acltest", new byte[0], ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                zk.close();
                zk = createClient();
                zk.addAuthInfo("digest", "ben:passwd2".getBytes());
                try {
                    zk.getData("/acltest", false, new Stat());
                    Assert.fail("Should have received a permission error");
                }
                catch (KeeperException e) {
                    Assert.assertEquals(KeeperException.Code.NOAUTH, e.getCode());
                }
                zk.addAuthInfo("digest", "ben:passwd".getBytes());
                zk.getData("/acltest", false, new Stat());
                zk.setACL("/acltest", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
                zk.close();
                zk = createClient();
                zk.getData("/acltest", false, new Stat());
                IList<ACL> acls = zk.getACL("/acltest", new Stat());
                Assert.assertEquals(1, acls.Count);
                Assert.assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, acls);
                zk.close();
            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }
        }

        private class MyWatcher : CountdownWatcher {
            internal readonly BlockingCollection<WatchedEvent> events = new BlockingCollection<WatchedEvent>();

            public override async Task process(WatchedEvent @event) {
                await base.process(@event);
                if (@event.get_Type() != Event.EventType.None) {
                    events.Add(@event);
                }
            }
        }

        /// <summary>
        /// Register multiple watchers and verify that they all get notified and
        /// in the right order.
        /// </summary>
        [Fact]
        public void testMutipleWatcherObjs() {
            ZooKeeper zk = createClient(new CountdownWatcher());
            try {
                MyWatcher[] watchers = new MyWatcher[100];
                MyWatcher[] watchers2 = new MyWatcher[watchers.Length];
                for (int i = 0; i < watchers.Length; i++) {
                    watchers[i] = new MyWatcher();
                    watchers2[i] = new MyWatcher();
                    zk.create("/foo-" + i, ("foodata" + i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                }
                Stat stat = new Stat();

                //
                // test get/exists with single set of watchers
                //   get all, then exists all
                //
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
                }
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull(zk.exists("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    zk.setData("/foo-" + i, ("foodata2-" + i).getBytes(), -1);
                    zk.setData("/foo-" + i, ("foodata3-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++) {
                    WatchedEvent @event = watchers[i].events.poll(10 * 1000);
                    Assert.assertEquals("/foo-" + i, @event.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, @event.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.assertEquals(0, watchers[i].events.size());
                }

                //
                // test get/exists with single set of watchers
                //  get/exists together
                //
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
                    Assert.assertNotNull(zk.exists("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    zk.setData("/foo-" + i, ("foodata4-" + i).getBytes(), -1);
                    zk.setData("/foo-" + i, ("foodata5-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++) {
                    WatchedEvent @event = watchers[i].events.poll(10 * 1000);
                    Assert.assertEquals("/foo-" + i, @event.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, @event.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.assertEquals(0, watchers[i].events.size());
                }

                //
                // test get/exists with two sets of watchers
                //
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull(zk.getData("/foo-" + i, watchers[i], stat));
                    Assert.assertNotNull(zk.exists("/foo-" + i, watchers2[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    zk.setData("/foo-" + i, ("foodata6-" + i).getBytes(), -1);
                    zk.setData("/foo-" + i, ("foodata7-" + i).getBytes(), -1);
                }
                for (int i = 0; i < watchers.Length; i++) {
                    WatchedEvent @event = watchers[i].events.poll(10 * 1000);
                    Assert.assertEquals("/foo-" + i, @event.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, @event.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.assertEquals(0, watchers[i].events.size());

                    // watchers2
                    WatchedEvent event2 = watchers2[i].events.poll(10 * 1000);
                    Assert.assertEquals("/foo-" + i, event2.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, event2.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, event2.getState());

                    // small chance that an unexpected message was delivered
                    //  after this check, but we would catch that next time
                    //  we check events
                    Assert.assertEquals(0, watchers2[i].events.size());
                }

            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }
        }


        private void performClientTest(bool withWatcherObj) {
            ZooKeeper zk = null;
            try {
                MyWatcher watcher = new MyWatcher();
                zk = createClient(watcher);
                LOG.info("Before create /benwashere");
                zk.create("/benwashere", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("After create /benwashere");
                try {
                    zk.setData("/benwashere", "hi".getBytes(), 57);
                    Assert.fail("Should have gotten BadVersion exception");
                }
                catch (KeeperException.BadVersionException) {
                    // expected that
                }
                catch (KeeperException) {
                    Assert.fail("Should have gotten BadVersion exception");
                }
                LOG.info("Before delete /benwashere");
                zk.delete("/benwashere", 0);
                LOG.info("After delete /benwashere");
                zk.close();
                //LOG.info("Closed client: " + zk.describeCNXN());
                Thread.Sleep(2000);

                zk = createClient(watcher);
                //LOG.info("Created a new client: " + zk.describeCNXN());
                LOG.info("Before delete /");

                try {
                    zk.delete("/", -1);
                    Assert.fail("deleted root!");
                }
                catch (KeeperException.BadArgumentsException) {
                    // good, expected that
                }
                Stat stat = new Stat();
                // Test basic create, ls, and getData
                zk.create("/pat", "Pat was here".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("Before create /ben");
                zk.create("/pat/ben", "Ben was here".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("Before getChildren /pat");
                List<string> children = zk.getChildren("/pat", false);
                Assert.assertEquals(1, children.Count);
                Assert.assertEquals("ben", children[0]);
                IList<string> children2 = zk.getChildren("/pat", false, null);
                Assert.assertEquals(children, children2);
                string value = Encoding.UTF8.GetString(zk.getData("/pat/ben", false, stat));
                Assert.assertEquals("Ben was here", value);
                // Test stat and watch of non existent node

                try {
                    if (withWatcherObj) {
                        Assert.assertEquals(null, zk.exists("/frog", watcher));
                    }
                    else {
                        Assert.assertEquals(null, zk.exists("/frog", true));
                    }
                    LOG.info("Comment: asseting passed for frog setting /");
                }
                catch (KeeperException.NoNodeException) {
                    // OK, expected that
                }
                zk.create("/frog", "hi".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                // the first poll is just a session delivery
                LOG.info("Comment: checking for events length " + watcher.events.size());
                WatchedEvent @event = watcher.events.poll(10 * 1000);
                Assert.assertEquals("/frog", @event.getPath());
                Assert.assertEquals(Watcher.Event.EventType.NodeCreated, @event.get_Type());
                Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
                // Test child watch and create with sequence
                zk.getChildren("/pat/ben", true);
                for (int i = 0; i < 10; i++) {
                    zk.create("/pat/ben/" + i + "-", Convert.ToString(i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL);
                }
                children = zk.getChildren("/pat/ben", false);
                children.Sort();
                Assert.assertEquals(10, children.Count);
                for (int i = 0; i < 10; i++) {


                    string name = children[i];
                    Assert.assertTrue("starts with -", name.StartsWith(i + "-", StringComparison.Ordinal));
                    byte[] b;
                    if (withWatcherObj) {
                        b = zk.getData("/pat/ben/" + name, watcher, stat);
                    }
                    else {
                        b = zk.getData("/pat/ben/" + name, true, stat);
                    }
                    Assert.assertEquals(i, int.Parse(Encoding.UTF8.GetString(b)));
                    zk.setData("/pat/ben/" + name, "new".getBytes(), stat.getVersion());
                    if (withWatcherObj) {
                        stat = zk.exists("/pat/ben/" + name, watcher);
                    }
                    else {
                        stat = zk.exists("/pat/ben/" + name, true);
                    }
                    zk.delete("/pat/ben/" + name, stat.getVersion());
                }
                @event = watcher.events.poll(10 * 1000);
                Assert.assertEquals("/pat/ben", @event.getPath());
                Assert.assertEquals(Watcher.Event.EventType.NodeChildrenChanged, @event.get_Type());
                Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
                for (int i = 0; i < 10; i++) {
                    @event = watcher.events.poll(10 * 1000);


                    string name = children[i];
                    Assert.assertEquals("/pat/ben/" + name, @event.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDataChanged, @event.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
                    @event = watcher.events.poll(10 * 1000);
                    Assert.assertEquals("/pat/ben/" + name, @event.getPath());
                    Assert.assertEquals(Watcher.Event.EventType.NodeDeleted, @event.get_Type());
                    Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
                }
                zk.create("/good\u0040path", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                zk.create("/duplicate", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                try {
                    zk.create("/duplicate", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    Assert.fail("duplicate create allowed");
                }
                catch (KeeperException.NodeExistsException) {
                    // OK, expected that
                }
            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }
        }

        // Test that sequential filenames are being created correctly,
        // with 0-padding in the filename

        [Fact]
        public void testSequentialNodeNames() {
            string path = "/SEQUENCE";
            string file = "TEST";
            string filepath = path + "/" + file;

            ZooKeeper zk = null;
            try {
                zk = createClient();
                zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.create(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                IList<string> children = zk.getChildren(path, false);
                Assert.assertEquals(1, children.Count);
                Assert.assertEquals(file + "0000000000", children[0]);

                zk.create(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                children = zk.getChildren(path, false);
                Assert.assertEquals(2, children.Count);
                Assert.assertTrue("contains child 1", children.Contains(file + "0000000001"));

                zk.create(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                children = zk.getChildren(path, false);
                Assert.assertEquals(3, children.Count);
                Assert.assertTrue("contains child 2", children.Contains(file + "0000000002"));

                // The pattern is holding so far.  Let's run the counter a bit
                // to be sure it continues to spit out the correct answer
                for (int i = children.Count; i < 105; i++) {
                    zk.create(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                }

                children = zk.getChildren(path, false);
                Assert.assertTrue("contains child 104", children.Contains(file + "0000000104"));

            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }
        }

        // Test that data provided when 
        // creating sequential nodes is stored properly
        [Fact]
        public void testSequentialNodeData() {
            ZooKeeper zk = null;
            const string queue_handle = "/queue";
            try {
                zk = createClient();

                zk.create(queue_handle, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.create(queue_handle + "/element", "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
                zk.create(queue_handle + "/element", "1".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
                IList<string> children = zk.getChildren(queue_handle, true);
                Assert.assertEquals(children.Count, 2);
                string child1 = children[0];
                string child2 = children[1];
                int compareResult = child1.CompareTo(child2);
                Assert.assertNotEquals(compareResult, 0);
                if (compareResult < 0) {
                }
                else {
                    string temp = child1;
                    child1 = child2;
                    child2 = temp;
                }
                string child1data = Encoding.UTF8.GetString(zk.getData(queue_handle + "/" + child1, false, null));
                string child2data = Encoding.UTF8.GetString(zk.getData(queue_handle + "/" + child2, false, null));
                Assert.assertEquals(child1data, "0");
                Assert.assertEquals(child2data, "1");
            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }

        }
        
        [Fact]
        public void testLargeNodeData() {
            ZooKeeper zk = null;
            const string queue_handle = "/large";
            try {
                zk = createClient();

                zk.create(queue_handle, new byte[500000], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            finally {
                if (zk != null) {
                    zk.close();
                }
            }

        }


        private void verifyCreateFails(string path, ZooKeeper zk) {
            try {
                zk.create(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            catch (ArgumentException) {
                // this is good
                return;
            }
            Assert.fail("bad path \"" + path + "\" not caught");
        }

        // Test that the path string is validated
        [Fact]
        public void testPathValidation() {
            ZooKeeper zk = createClient();

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

            zk.create("/createseqpar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // next two steps - related to sequential processing
            // 1) verify that empty child name Assert.fails if not sequential
            try {
                zk.create("/createseqpar/", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }

            // 2) verify that empty child name success if sequential 
            zk.create("/createseqpar/", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            zk.create("/createseqpar/.", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            zk.create("/createseqpar/..", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            try {
                zk.create("/createseqpar//", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
            try {
                zk.create("/createseqpar/./", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
            try {
                zk.create("/createseqpar/../", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
        }

        //    private void notestConnections()
        //        throws IOException, InterruptedException, KeeperException
        //    {
        //        ZooKeeper zk;
        //        for(int i = 0; i < 2000; i++) {
        //            if (i % 100 == 0) {
        //                LOG.info("Testing " + i + " connections");
        //            }
        //            // We want to make sure socket descriptors are going away
        //            zk = new ZooKeeper(hostPort, 30000, this);
        //            zk.getData("/", false, new Stat());
        //            zk.close();
        //        }
        //    }

        [Fact]
        public void testDeleteWithChildren() {
            ZooKeeper zk = createClient();
            zk.create("/parent", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/parent/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            try {
                zk.delete("/parent", -1);
                Assert.fail("Should have received a not equals message");
            }
            catch (KeeperException e) {
                Assert.assertEquals(KeeperException.Code.NOTEMPTY, e.getCode());
            }
            zk.delete("/parent/child", -1);
            zk.delete("/parent", -1);
            zk.close();
        }
        
        /// <summary>
        /// We create a perfectly valid 'exists' request, except that the opcode is wrong.
        /// @return </summary>
        /// <exception cref="Exception"> </exception>
        [Fact]
        public void testNonExistingOpCode() {
            TestableZooKeeper zk = createClient();

            const string path = "/m1";

            RequestHeader h = new RequestHeader();
            h.set_Type(888); // This code does not exists
            ExistsRequest request = new ExistsRequest();
            request.setPath(path);
            request.setWatch(false);
            ExistsResponse response = new ExistsResponse();
            ReplyHeader r = zk.submitRequest(h, request, response, null);

            Assert.assertEquals(r.getErr(), (int) KeeperException.Code.UNIMPLEMENTED);

            try {
                zk.exists("/m1", false);
                Assert.fail("The connection should have been closed");
            }
            catch (KeeperException.ConnectionLossException) {
            }
        }
    }

}