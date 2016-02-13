using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
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
        private static readonly ILogProducer LOG = TypeLogger<ClientTest>.Instance;

        /// <summary>
        /// Verify that pings are sent, keeping the "idle" client alive </summary>
        [Fact]
        public async Task testPing() {
                ZooKeeper zkIdle = await createClient();

                ZooKeeper zkWatchCreator = await createClient();

                for (int i = 0; i < 10; i++) {
                    await zkWatchCreator.createAsync("/" + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
                for (int i = 0; i < 10; i++) {
                    await zkIdle.existsAsync("/" + i, true);
                }
                for (int i = 0; i < 10; i++) {
                    await Task.Delay(1000);
                    await zkWatchCreator.deleteAsync("/" + i, -1);
                }
                // The bug will manifest itself here because zkIdle will expire
                await zkIdle.existsAsync("/0", false);
        }



        [Fact]
        public Task testClientwithoutWatcherObj() {
            return performClientTest(false);
        }



        [Fact]
        public Task testClientWithWatcherObj() {
            return performClientTest(true);
        }
        

        [Fact]
        public async Task testACLs() {
            ZooKeeper zk = await createClient();
                try {
                    await zk.createAsync("/acltest", new byte[0], ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                    Assert.fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e) {
                    LOG.info("Test successful, invalid acl received : " + e.Message);
                }
                try {
                    List<ACL> testACL = new List<ACL>();
                    testACL.Add(new ACL((int) (ZooDefs.Perms.ALL | ZooDefs.Perms.ADMIN), ZooDefs.Ids.AUTH_IDS));
                    testACL.Add(new ACL((int) (ZooDefs.Perms.ALL | ZooDefs.Perms.ADMIN), new Id("ip", "127.0.0.1/8")));
                    await zk.createAsync("/acltest", new byte[0], testACL, CreateMode.PERSISTENT);
                    Assert.fail("Should have received an invalid acl error");
                }
                catch (KeeperException.InvalidACLException e) {
                    LOG.info("Test successful, invalid acl received : " + e.Message);
                }
                zk.addAuthInfo("digest", "ben:passwd".UTF8getBytes());
                await zk.createAsync("/acltest", new byte[0], ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
                await zk.closeAsync();

                zk = await createClient();
                zk.addAuthInfo("digest", "ben:passwd2".UTF8getBytes());
                try {
                    await zk.getDataAsync("/acltest", false);
                    Assert.fail("Should have received a permission error");
                }
                catch (KeeperException e) {
                    Assert.assertEquals(KeeperException.Code.NOAUTH, e.getCode());
                }
                zk.addAuthInfo("digest", "ben:passwd".UTF8getBytes());
                await zk.getDataAsync("/acltest", false);
                await zk.setACLAsync("/acltest", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
                await zk.closeAsync();

                zk = await createClient();
                await zk.getDataAsync("/acltest", false);
                IList<ACL> acls = (await zk.getACLAsync("/acltest")).Acls;
                Assert.assertEquals(1, acls.Count);
                Assert.assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, acls);

                // The stat parameter should be optional.
                acls = (await zk.getACLAsync("/acltest")).Acls;
                Assert.assertEquals(1, acls.Count);
                Assert.assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, acls);
        }

        private class MyWatcher : Watcher {
            internal readonly BlockingCollection<WatchedEvent> events = new BlockingCollection<WatchedEvent>();

            public override Task process(WatchedEvent @event) {
                if (@event.get_Type() != Event.EventType.None) {
                    events.Add(@event);
                }
                return CompletedTask;
            }
        }

        /// <summary>
        /// Register multiple watchers and verify that they all get notified and
        /// in the right order.
        /// </summary>
        [Fact]
        public async Task testMutipleWatcherObjs() {
            ZooKeeper zk = await createClient();
                MyWatcher[] watchers = new MyWatcher[100];
                MyWatcher[] watchers2 = new MyWatcher[watchers.Length];
                for (int i = 0; i < watchers.Length; i++) {
                    watchers[i] = new MyWatcher();
                    watchers2[i] = new MyWatcher();
                    await zk.createAsync("/foo-" + i, ("foodata" + i).UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                }

                //
                // test get/exists with single set of watchers
                //   get all, then exists all
                //
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull((await zk.getDataAsync("/foo-" + i, watchers[i])).Data);
            }
                for (int i = 0; i < watchers.Length; i++) {
                    Assert.assertNotNull(await zk.existsAsync("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    await zk.setDataAsync("/foo-" + i, ("foodata2-" + i).UTF8getBytes(), -1);
                    await zk.setDataAsync("/foo-" + i, ("foodata3-" + i).UTF8getBytes(), -1);
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
                    Assert.assertNotNull((await zk.getDataAsync("/foo-" + i, watchers[i])).Data);
                Assert.assertNotNull(await zk.existsAsync("/foo-" + i, watchers[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    await zk.setDataAsync("/foo-" + i, ("foodata4-" + i).UTF8getBytes(), -1);
                    await zk.setDataAsync("/foo-" + i, ("foodata5-" + i).UTF8getBytes(), -1);
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
                    Assert.assertNotNull((await zk.getDataAsync("/foo-" + i, watchers[i])).Data);
                Assert.assertNotNull(await zk.existsAsync("/foo-" + i, watchers2[i]));
                }
                // trigger the watches
                for (int i = 0; i < watchers.Length; i++) {
                    await zk.setDataAsync("/foo-" + i, ("foodata6-" + i).UTF8getBytes(), -1);
                    await zk.setDataAsync("/foo-" + i, ("foodata7-" + i).UTF8getBytes(), -1);
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


        private async Task performClientTest(bool withWatcherObj) {
            ZooKeeper zk = null;
                MyWatcher watcher = new MyWatcher();
                zk = await createClient(watcher);
                LOG.info("Before create /benwashere");
                await zk.createAsync("/benwashere", "".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("After create /benwashere");
                try {
                    await zk.setDataAsync("/benwashere", "hi".UTF8getBytes(), 57);
                    Assert.fail("Should have gotten BadVersion exception");
                }
                catch (KeeperException.BadVersionException) {
                    // expected that
                }
                catch (KeeperException) {
                    Assert.fail("Should have gotten BadVersion exception");
                }
                LOG.info("Before delete /benwashere");
                await zk.deleteAsync("/benwashere", 0);
                LOG.info("After delete /benwashere");
                //LOG.info("Closed client: " + zk.describeCNXN());
                await Task.Delay(2000);

                zk = await createClient(watcher);
                //LOG.info("Created a new client: " + zk.describeCNXN());
                LOG.info("Before delete /");

                try {
                    await zk.deleteAsync("/", -1);
                    Assert.fail("deleted root!");
                }
                catch (KeeperException.BadArgumentsException) {
                    // good, expected that
                }

                // Test basic create, ls, and getData
                await zk.createAsync("/pat", "Pat was here".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("Before create /ben");
                await zk.createAsync("/pat/ben", "Ben was here".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                LOG.info("Before getChildren /pat");
                List<string> children = (await zk.getChildrenAsync("/pat", false)).Children;
                Assert.assertEquals(1, children.Count);
                Assert.assertEquals("ben", children[0]);
                IList<string> children2 = (await zk.getChildrenAsync("/pat", false)).Children;
                Assert.assertEquals(children, children2);
                Assert.assertEquals("Ben was here", (await zk.getDataAsync("/pat/ben", false)).Data.UTF8bytesToString());
                // Test stat and watch of non existent node

                try {
                    if (withWatcherObj) {
                        Assert.assertEquals(null, await zk.existsAsync("/frog", watcher));
                    }
                    else {
                        Assert.assertEquals(null, await zk.existsAsync("/frog", true));
                    }
                    LOG.info("Comment: asseting passed for frog setting /");
                }
                catch (KeeperException.NoNodeException) {
                    // OK, expected that
                }
                await zk.createAsync("/frog", "hi".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                // the first poll is just a session delivery
                LOG.info("Comment: checking for events length " + watcher.events.size());
                WatchedEvent @event = watcher.events.poll(10 * 1000);
                Assert.assertEquals("/frog", @event.getPath());
                Assert.assertEquals(Watcher.Event.EventType.NodeCreated, @event.get_Type());
                Assert.assertEquals(Watcher.Event.KeeperState.SyncConnected, @event.getState());
                // Test child watch and create with sequence
                await zk.getChildrenAsync("/pat/ben", true);
                for (int i = 0; i < 10; i++) {
                    await zk.createAsync("/pat/ben/" + i + "-", Convert.ToString(i).UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL);
                }
                children = (await zk.getChildrenAsync("/pat/ben", false)).Children;
                children.Sort();
                Assert.assertEquals(10, children.Count);
                for (int i = 0; i < 10; i++) {


                    string name = children[i];
                    Assert.assertTrue("starts with -", name.StartsWith(i + "-", StringComparison.Ordinal));
                    DataResult dataResult;
                    if (withWatcherObj) {
                        dataResult = await zk.getDataAsync("/pat/ben/" + name, watcher);
                    }
                    else {
                        dataResult = await zk.getDataAsync("/pat/ben/" + name, true);
                    }
                    Assert.assertEquals(i, int.Parse(dataResult.Data.UTF8bytesToString()));
                    await zk.setDataAsync("/pat/ben/" + name, "new".UTF8getBytes(), dataResult.Stat.getVersion());
                    Stat stat;
                    if (withWatcherObj) {
                        stat = await zk.existsAsync("/pat/ben/" + name, watcher);
                    }
                    else {
                        stat = await zk.existsAsync("/pat/ben/" + name, true);
                    }
                    await zk.deleteAsync("/pat/ben/" + name, stat.getVersion());
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
                await zk.createAsync("/good\u0040path", "".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                await zk.createAsync("/duplicate", "".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                try {
                    await zk.createAsync("/duplicate", "".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    Assert.fail("duplicate create allowed");
                }
                catch (KeeperException.NodeExistsException) {
                    // OK, expected that
                }
        }

        // Test that sequential filenames are being created correctly,
        // with 0-padding in the filename

        [Fact]
        public async Task testSequentialNodeNames() {
            string path = "/SEQUENCE";
            string file = "TEST";
            string filepath = path + "/" + file;

            ZooKeeper zk = await createClient();
                await zk.createAsync(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                await zk.createAsync(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                IList<string> children = (await zk.getChildrenAsync(path, false)).Children;
                Assert.assertEquals(1, children.Count);
                Assert.assertEquals(file + "0000000000", children[0]);

                await zk.createAsync(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                children = (await zk.getChildrenAsync(path, false)).Children;
                Assert.assertEquals(2, children.Count);
                Assert.assertTrue("contains child 1", children.Contains(file + "0000000001"));

                await zk.createAsync(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                children = (await zk.getChildrenAsync(path, false)).Children;
                Assert.assertEquals(3, children.Count);
                Assert.assertTrue("contains child 2", children.Contains(file + "0000000002"));

                // The pattern is holding so far.  Let's run the counter a bit
                // to be sure it continues to spit out the correct answer
                for (int i = children.Count; i < 105; i++) {
                    await zk.createAsync(filepath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                }

                children = (await zk.getChildrenAsync(path, false)).Children;
                Assert.assertTrue("contains child 104", children.Contains(file + "0000000104"));
        }

        // Test that data provided when 
        // creating sequential nodes is stored properly
        [Fact]
        public async Task testSequentialNodeData() {
            const string queue_handle = "/queue";
                ZooKeeper zk = await createClient();

                await zk.createAsync(queue_handle, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                await zk.createAsync(queue_handle + "/element", "0".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
                await zk.createAsync(queue_handle + "/element", "1".UTF8getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
                IList<string> children = (await zk.getChildrenAsync(queue_handle, true)).Children;
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
                string child1data = (await zk.getDataAsync(queue_handle + "/" + child1, false)).Data.UTF8bytesToString();
                string child2data = (await zk.getDataAsync(queue_handle + "/" + child2, false)).Data.UTF8bytesToString();
                Assert.assertEquals(child1data, "0");
                Assert.assertEquals(child2data, "1");
        }
        
        [Fact]
        public async Task testLargeNodeData() {
            ZooKeeper zk = null;
            const string queue_handle = "/large";
                zk = await createClient();

                await zk.createAsync(queue_handle, new byte[500000], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }


        private async Task verifyCreateFails(string path, ZooKeeper zk) {
            try {
                await zk.createAsync(path, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            catch (ArgumentException) {
                // this is good
                return;
            }
            Assert.fail("bad path \"" + path + "\" not caught");
        }

        // Test that the path string is validated
        [Fact]
        public async Task testPathValidation() {
            ZooKeeper zk = await createClient();
            await Task.WhenAll(
                verifyCreateFails(null, zk),
                verifyCreateFails("", zk),
                verifyCreateFails("//", zk),
                verifyCreateFails("///", zk),
                verifyCreateFails("////", zk),
                verifyCreateFails("/.", zk),
                verifyCreateFails("/..", zk),
                verifyCreateFails("/./", zk),
                verifyCreateFails("/../", zk),
                verifyCreateFails("/foo/./", zk),
                verifyCreateFails("/foo/../", zk),
                verifyCreateFails("/foo/.", zk),
                verifyCreateFails("/foo/..", zk),
                verifyCreateFails("/./.", zk),
                verifyCreateFails("/../..", zk),
                verifyCreateFails("/\u0001foo", zk),
                verifyCreateFails("/foo/bar/", zk),
                verifyCreateFails("/foo//bar", zk),
                verifyCreateFails("/foo/bar//", zk),
                verifyCreateFails("foo", zk),
                verifyCreateFails("a", zk));

            await zk.createAsync("/createseqpar", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // next two steps - related to sequential processing
            // 1) verify that empty child name Assert.fails if not sequential
            try {
                await zk.createAsync("/createseqpar/", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }

            // 2) verify that empty child name success if sequential 
            await zk.createAsync("/createseqpar/", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            await zk.createAsync("/createseqpar/.", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            await zk.createAsync("/createseqpar/..", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            try {
                await zk.createAsync("/createseqpar//", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
            try {
                await zk.createAsync("/createseqpar/./", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
            try {
                await zk.createAsync("/createseqpar/../", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                Assert.assertTrue(false);
            }
            catch (ArgumentException) {
                // catch this.
            }
        }

        [Fact]
        public async Task testDeleteWithChildren() {
            ZooKeeper zk = await createClient();
            await zk.createAsync("/parent", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            await zk.createAsync("/parent/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            try {
                await zk.deleteAsync("/parent", -1);
                Assert.fail("Should have received a not equals message");
            }
            catch (KeeperException e) {
                Assert.assertEquals(KeeperException.Code.NOTEMPTY, e.getCode());
            }
            await zk.deleteAsync("/parent/child", -1);
            await zk.deleteAsync("/parent", -1);
        }
        
        /// <summary>
        /// We create a perfectly valid 'exists' request, except that the opcode is wrong.
        /// @return </summary>
        /// <exception cref="Exception"> </exception>
        [Fact]
        public async Task testNonExistingOpCode() {
            ZooKeeper zk = await createClient();

            const string path = "/m1";

            RequestHeader h = new RequestHeader();
            h.set_Type(888); // This code does not exists
            ExistsRequest request = new ExistsRequest();
            request.setPath(path);
            request.setWatch(false);
            ExistsResponse response = new ExistsResponse();
            ReplyHeader r = await zk.cnxn.submitRequest(h, request, response, null);

            Assert.assertEquals(r.getErr(), (int) KeeperException.Code.UNIMPLEMENTED);

            try {
                await zk.existsAsync("/m1", false);
                Assert.fail("The connection should have been closed");
            }
            catch (KeeperException.ConnectionLossException) {
            }
        }
    }

}