using System;
using System.Collections.Generic;
using System.Threading;

using System.Threading.Tasks;
using org.apache.utils;
using org.apache.zookeeper;
using org.apache.zookeeper.data;
using Xunit;
using Assert = Xunit.Assert;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace org.apache.zookeeper.test
{

    public sealed class MultiTransactionTest : ClientBase
    {
        private static readonly ILogProducer LOG = TypeLogger<MultiTransactionTest>.Instance;

        private ZooKeeper zk;
        private ZooKeeper zk_chroot;


        public MultiTransactionTest()
        {
            zk = createClient();
        }


        private List<OpResult> multi(ZooKeeper zk, List<Op> ops)
        {
            return zk.multi(ops);
        }

        private void multiHavingErrors(ZooKeeper zk, List<Op> ops, List<KeeperException.Code> expectedResultCodes)
        {
            try
            {
                multi(zk, ops);
                Assert.fail("Shouldn't have validated in ZooKeeper client!");
            }
            catch (KeeperException e)
            {
                var results = e.getResults();
                for (int i = 0; i < results.Count; i++)
                {
                    OpResult opResult = results[i];
                    Assert.assertTrue("Did't recieve proper error response", opResult is OpResult.ErrorResult);
                    OpResult.ErrorResult errRes = (OpResult.ErrorResult)opResult;
                    Assert.assertEquals("Did't recieve proper error code", expectedResultCodes[i], errRes.getErr());
                }
            }
        }

        private List<OpResult> commit(Transaction txn)
        {
            return txn.commit();
        }

        /// <summary>
        /// Test verifies the multi calls with invalid znode path
        /// </summary>
        [Fact]
        public void testInvalidPath()
        {
            List<KeeperException.Code> expectedResultCodes = new List<KeeperException.Code>();
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);
            expectedResultCodes.Add(KeeperException.Code.BADARGUMENTS);
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);
            // create with CreateMode
            List<Op> opList = Arrays.asList(Op.create("/multi0", new byte[0],
                                                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                    CreateMode.PERSISTENT),
                                          Op.create(
                                                    "/multi1/", new byte[0],
                                                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                    CreateMode.PERSISTENT),
                                          Op.create("/multi2", new byte[0],
                                                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                    CreateMode.PERSISTENT));
            multiHavingErrors(zk, opList, expectedResultCodes);

            // create with valid sequential flag
            opList = Arrays.asList(Op.create("/multi0", new byte[0],
                                             ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.PERSISTENT),
                                   Op.create("multi1/", new byte[0],
                                             ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.EPHEMERAL_SEQUENTIAL.toFlag()),
                                   Op.create("/multi2",
                                             new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                             CreateMode.PERSISTENT));
            multiHavingErrors(zk, opList, expectedResultCodes);

            // check
            opList = Arrays.asList(Op.check("/multi0", -1),
                                   Op.check("/multi1/", 100),
                                   Op.check("/multi2", 5));
            multiHavingErrors(zk, opList, expectedResultCodes);

            // delete
            opList = Arrays.asList(Op.delete("/multi0", -1),
                                   Op.delete("/multi1/", 100),
                                   Op.delete("/multi2", 5));
            multiHavingErrors(zk, opList, expectedResultCodes);

            // Multiple bad arguments
            expectedResultCodes.Add(KeeperException.Code.BADARGUMENTS);

            // setdata
            opList = Arrays.asList(Op.setData("/multi0", new byte[0], -1),
                                   Op.setData("/multi1/", new byte[0], -1),
                                   Op.setData("/multi2", new byte[0], -1),
                                   Op.setData("multi3", new byte[0], -1));
            multiHavingErrors(zk, opList, expectedResultCodes);
        }

        /// <summary>
        /// Test verifies the multi calls with blank znode path
        /// </summary>:
        [Fact]
        public void testBlankPath()
        {
            List<KeeperException.Code> expectedResultCodes = new List<KeeperException.Code>();
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);
            expectedResultCodes.Add(KeeperException.Code.BADARGUMENTS);
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);
            expectedResultCodes.Add(KeeperException.Code.BADARGUMENTS);

            // delete
            List<Op> opList = Arrays.asList(Op.delete("/multi0", -1),
                                            Op.delete(null, 100),
                                            Op.delete("/multi2", 5),
                                            Op.delete("", -1));
            multiHavingErrors(zk, opList, expectedResultCodes);
        }


        /// <summary>
        /// Test verifies the multi.create with invalid createModeFlag
        /// </summary>
        [Fact]
        public void testInvalidCreateModeFlag()
        {
            List<KeeperException.Code> expectedResultCodes = new List<KeeperException.Code>();
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);
            expectedResultCodes.Add(KeeperException.Code.BADARGUMENTS);
            expectedResultCodes.Add(KeeperException.Code.RUNTIMEINCONSISTENCY);

            int createModeFlag = 6789;
            List<Op> opList = Arrays.asList(Op.create("/multi0", new byte[0],
                                                      ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                      CreateMode.PERSISTENT),
                                            Op.create("/multi1", new byte[0],
                                                      ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                      createModeFlag),
                                            Op.create("/multi2", new byte[0],
                                                      ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                                      CreateMode.PERSISTENT));
            multiHavingErrors(zk, opList, expectedResultCodes);
        }

        /**
         * ZOOKEEPER-2052:
         * Multi abort shouldn't have any side effect.
         * We fix a bug in rollback and the following scenario should work:
         * 1. multi delete abort because of not empty directory
         * 2. ephemeral nodes under that directory are deleted
         * 3. multi delete should succeed.
         */
        [Fact]
        public void testMultiRollback()
        {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            ZooKeeper epheZk = createClient();
            epheZk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

            List<Op> opList = Arrays.asList(Op.delete("/foo", -1));
            try
            {
                multi(zk, opList);
                Assert.fail("multi delete should failed for not empty directory");
            }
            catch (KeeperException.NotEmptyException)
            {
            }

            var hasBeenDeleted = new HasBeenDeletedWatcher();

            zk.exists("/foo/bar", hasBeenDeleted);

            epheZk.close();

            hasBeenDeleted.triggered.Wait();

            try
            {
                zk.getData("/foo/bar", false, null);
                Assert.fail("ephemeral node should have been deleted");
            }
            catch (KeeperException.NoNodeException)
            {
            }

            multi(zk, opList);

            try
            {
                zk.getData("/foo", false, null);
                Assert.fail("persistent node should have been deleted after multi");
            }
            catch (KeeperException.NoNodeException)
            {
            }
        }


        [Fact]
        public void testChRootCreateDelete()
        {
            // creating the subtree for chRoot clients.
            string chRoot = createNameSpace();
            // Creating child using chRoot client.
            zk_chroot = createClient(chRoot);
            Op createChild = Op.create("/myid", new byte[0],
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            multi(zk_chroot, Arrays.asList(createChild));

            Assert.assertNotNull("zNode is not created under chroot:" + chRoot, zk
                    .exists(chRoot + "/myid", false));
            Assert.assertNotNull("zNode is not created under chroot:" + chRoot,
                    zk_chroot.exists("/myid", false));
            Assert.assertNull("zNode is created directly under '/', ignored configured chroot",
                    zk.exists("/myid", false));

            // Deleting child using chRoot client.
            Op deleteChild = Op.delete("/myid", 0);
            multi(zk_chroot, Arrays.asList(deleteChild));
            Assert.assertNull("zNode exists under chroot:" + chRoot, zk.exists(
                    chRoot + "/myid", false));
            Assert.assertNull("zNode exists under chroot:" + chRoot, zk_chroot
                    .exists("/myid", false));
        }

        [Fact]
        public void testChRootSetData()
        {
            // creating the subtree for chRoot clients.
            string chRoot = createNameSpace();
            // setData using chRoot client.
            zk_chroot = createClient(chRoot);
            string[] names = new[] { "/multi0", "/multi1", "/multi2" };
            List<Op> ops = new List<Op>();

            for (int i = 0; i < names.Length; i++)
            {
                ops.Add(Op.create(names[i], new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT));
                ops.Add(Op.setData(names[i], names[i].UTF8getBytes(), 0));
            }

            multi(zk_chroot, ops);

            for (int i = 0; i < names.Length; i++)
            {
                Assert.assertEquals("zNode data not matching", names[i]
                    .UTF8getBytes(), zk_chroot.getData(names[i], false, null));
            }
        }

        [Fact]
        public void testChRootCheck()
        {
            // creating the subtree for chRoot clients.
            string chRoot = createNameSpace();
            // checking the child version using chRoot client.
            zk_chroot = createClient(chRoot);
            string[] names = { "/multi0", "/multi1", "/multi2" };
            List<Op> ops = new List<Op>();
            for (int i = 0; i < names.Length; i++)
            {
                zk.create(chRoot + names[i], new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
            for (int i = 0; i < names.Length; i++)
            {
                ops.Add(Op.check(names[i], 0));
            }
            multi(zk_chroot, ops);
        }

        [Fact]
        public void testChRootTransaction()
        {
            // creating the subtree for chRoot clients.
            string chRoot = createNameSpace();
            // checking the child version using chRoot client.
            zk_chroot = createClient(chRoot);
            const string childPath = "/myid";
            Transaction transaction = zk_chroot.transaction();
            transaction.create(childPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            transaction.check(childPath, 0);
            transaction.setData(childPath, childPath.UTF8getBytes(), 0);
            commit(transaction);

            Assert.assertNotNull("zNode is not created under chroot:" + chRoot, zk
                    .exists(chRoot + childPath, false));
            Assert.assertNotNull("zNode is not created under chroot:" + chRoot,
                    zk_chroot.exists(childPath, false));
            Assert.assertNull("zNode is created directly under '/', ignored configured chroot",
                            zk.exists(childPath, false));
            Assert.assertEquals("zNode data not matching", childPath
            .UTF8getBytes(), zk_chroot.getData(childPath, false, null));

            transaction = zk_chroot.transaction();
            // Deleting child using chRoot client.
            transaction.delete(childPath, 1);
            commit(transaction);

            Assert.assertNull("chroot:" + chRoot + " exists after delete", zk
                    .exists(chRoot + "/myid", false));
            Assert.assertNull("chroot:" + chRoot + " exists after delete",
                    zk_chroot.exists("/myid", false));
        }

        private string createNameSpace()
        {
            // creating the subtree for chRoot clients.
            String chRoot = "/appsX";
            Op createChRoot = Op.create(chRoot, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            multi(zk, Arrays.asList(createChRoot));
            return chRoot;
        }

        [Fact]
        public void testCreate()
        {
            multi(zk, Arrays.asList(
                    Op.create("/multi0", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create("/multi1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create("/multi2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                    ));
            zk.getData("/multi0", false, null);
            zk.getData("/multi1", false, null);
            zk.getData("/multi2", false, null);
        }

        [Fact]
        public void testCreateDelete()
        {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0)
                    ));

            // '/multi' should have been deleted
            Assert.assertNull(zk.exists("/multi", null));
        }

        [Fact]
        public void testInvalidVersion()
        {

            try
            {
                multi(zk, Arrays.asList(
                        Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                        Op.delete("/multi", 1)
                ));
                Assert.fail("delete /multi should have failed");
            }
            catch (KeeperException)
            {
                /* PASS */
            }
        }

        [Fact]
        public void testNestedCreate()
        {
            multi(zk, Arrays.asList(
                    /* Create */
                    Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create("/multi/a", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.create("/multi/a/1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),

                    /* Delete */
                    Op.delete("/multi/a/1", 0),
                    Op.delete("/multi/a", 0),
                    Op.delete("/multi", 0)
                    ));

            //Verify tree deleted
            Assert.assertNull(zk.exists("/multi/a/1", null));
            Assert.assertNull(zk.exists("/multi/a", null));
            Assert.assertNull(zk.exists("/multi", null));
        }

        [Fact]
        public void testSetData()
        {

            string[] names = new string[] { "/multi0", "/multi1", "/multi2" };
            List<Op> ops = new List<Op>();

            for (int i = 0; i < names.Length; i++)
            {
                ops.Add(Op.create(names[i], new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
                ops.Add(Op.setData(names[i], names[i].UTF8getBytes(), 0));
            }

            multi(zk, ops);

            for (int i = 0; i < names.Length; i++)
            {
                Assert.assertEquals(names[i].UTF8getBytes(), zk.getData(names[i], false, null));
            }
        }

        [Fact]
        public void testUpdateConflict()
        {

            Assert.assertNull(zk.exists("/multi", null));

            try
            {
                multi(zk, Arrays.asList(
                        Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                        Op.setData("/multi", "X".UTF8getBytes(), 0),
                        Op.setData("/multi", "Y".UTF8getBytes(), 0)
                        ));
                Assert.fail("Should have thrown a KeeperException for invalid version");
            }
            catch (KeeperException e)
            {
                //PASS
                LOG.error("STACKTRACE: " + e);
            }

            Assert.assertNull(zk.exists("/multi", null));

            //Updating version solves conflict -- order matters
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.setData("/multi", "X".UTF8getBytes(), 0),
                    Op.setData("/multi", "Y".UTF8getBytes(), 1)
                    ));

            Assert.assertEquals(zk.getData("/multi", false, null), "Y".UTF8getBytes());
        }

        [Fact]
        public void TestDeleteUpdateConflict()
        {

            /* Delete of a node folowed by an update of the (now) deleted node */
            try
            {
                multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0),
                    Op.setData("/multi", "Y".UTF8getBytes(), 0)
                    ));
                Assert.fail("/multi should have been deleted so setData should have failed");
            }
            catch (KeeperException)
            {
                /* PASS */
            }

            // '/multi' should never have been created as entire op should fail
            Assert.assertNull(zk.exists("/multi", null));
        }

        [Fact]
        public void TestGetResults()
        {
            /* Delete of a node folowed by an update of the (now) deleted node */
            var ops = Arrays.asList(
                    Op.create("/multi", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0),
                    Op.setData("/multi", "Y".UTF8getBytes(), 0),
                    Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
            );
            List<OpResult> results = null;
            try
            {
                zk.multi(ops);
                Assert.fail("/multi should have been deleted so setData should have failed");
            }
            catch (KeeperException e)
            {
                // '/multi' should never have been created as entire op should fail
                Assert.assertNull(zk.exists("/multi", null));
                results = e.getResults();
            }

            Assert.assertNotNull(results);
            foreach (OpResult r in results)
            {
                LOG.info("RESULT==> " + r);
                if (r is OpResult.ErrorResult)
                {
                    OpResult.ErrorResult er = (OpResult.ErrorResult)r;
                    LOG.info("ERROR RESULT: " + er + " ERR=>" + EnumUtil<KeeperException.Code>.DefinedCast(er.getErr()));
                }
            }
        }

        [Fact]
        public void testWatchesTriggered()
        {
            HasTriggeredWatcher watcher = new HasTriggeredWatcher();
            zk.getChildren("/", watcher);
            multi(zk, Arrays.asList(
                    Op.create("/t", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/t", -1)
            ));
            Assert.assertTrue(watcher.triggered.Wait(CONNECTION_TIMEOUT));
        }

        [Fact]
        public void testNoWatchesTriggeredForFailedMultiRequest()
        {
            HasTriggeredWatcher watcher = new HasTriggeredWatcher();
            zk.getChildren("/", watcher);
            try
            {
                multi(zk, Arrays.asList(
                        Op.create("/t", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                        Op.delete("/nonexisting", -1)
                ));
                Assert.fail("expected previous multi op to fail!");
            }
            catch (KeeperException.NoNodeException)
            {
                // expected
            }
            SyncCallback cb = new SyncCallback();
            zk.sync("/").ContinueWith(t => cb.processResult());

            // by waiting for the callback we're assured that the event queue is flushed
            cb.done.Wait(CONNECTION_TIMEOUT);
            Assert.assertEquals(false, watcher.triggered.IsSet);
        }

        [Fact]
        public void testTransactionBuilder()
        {
            List<OpResult> results = commit(zk.transaction()
                    .create("/t1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                    .create("/t1/child", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                    .create("/t2", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
            Assert.assertEquals(3, results.Count);
            foreach (OpResult r in results)
            {
                OpResult.CreateResult c = (OpResult.CreateResult)r;
                Assert.assertTrue(c.getPath().StartsWith("/t"));
                Assert.assertNotNull(c.ToString());
            }
            Assert.assertNotNull(zk.exists("/t1", false));
            Assert.assertNotNull(zk.exists("/t1/child", false));
            Assert.assertNotNull(zk.exists("/t2", false));

            results = commit(zk.transaction()
                    .check("/t1", 0)
                    .check("/t1/child", 0)
                    .check("/t2", 0));
            Assert.assertEquals(3, results.Count);
            foreach (OpResult r in results)
            {
                OpResult.CheckResult c = (OpResult.CheckResult)r;
                Assert.assertNotNull(c.ToString());
            }

            try
            {
                results = commit(zk.transaction()
                        .check("/t1", 0)
                        .check("/t1/child", 0)
                        .check("/t2", 1));
                Assert.fail();
            }
            catch (KeeperException.BadVersionException)
            {
                // expected
            }

            results = commit(zk.transaction()
                    .check("/t1", 0)
                    .setData("/t1", new byte[0], 0));
            Assert.assertEquals(2, results.Count);
            foreach (OpResult r in results)
            {
                Assert.assertNotNull(r.ToString());
            }

            try
            {
                results = commit(zk.transaction()
                        .check("/t1", 1)
                        .setData("/t1", new byte[0], 2));
                Assert.fail();
            }
            catch (KeeperException.BadVersionException)
            {
                // expected
            }

            results = commit(zk.transaction()
                    .check("/t1", 1)
                    .check("/t1/child", 0)
                    .check("/t2", 0));
            Assert.assertEquals(3, results.Count);

            results = commit(zk.transaction()
                    .delete("/t2", -1)
                    .delete("/t1/child", -1));
            Assert.assertEquals(2, results.Count);
            foreach (OpResult r in results)
            {
                OpResult.DeleteResult d = (OpResult.DeleteResult)r;
                Assert.assertNotNull(d.ToString());
            }
            Assert.assertNotNull(zk.exists("/t1", false));
            Assert.assertNull(zk.exists("/t1/child", false));
            Assert.assertNull(zk.exists("/t2", false));
        }

        private class HasTriggeredWatcher : Watcher
        {
            internal readonly ManualResetEventSlim triggered = new ManualResetEventSlim(false);

            public override Task process(WatchedEvent @event)
            {
                triggered.Set();
                return CompletedTask;
            }
        }

        private class HasBeenDeletedWatcher : Watcher
        {
            internal readonly ManualResetEventSlim triggered = new ManualResetEventSlim(false);

            public override Task process(WatchedEvent @event)
            {
                if (@event.get_Type() == Watcher.Event.EventType.NodeDeleted)
                {
                    triggered.Set();
                }
                return CompletedTask;
            }
        }

        private class SyncCallback
        {
            internal readonly ManualResetEventSlim done = new ManualResetEventSlim(false);

            public void processResult()
            {
                done.Set();
            }
        }
    }

}