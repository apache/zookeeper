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

package org.apache.zookeeper.test;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.OpResult.CheckResult;
import org.apache.zookeeper.OpResult.CreateResult;
import org.apache.zookeeper.OpResult.DeleteResult;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.OpResult.SetDataResult;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.ZKParameterized;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ZKParameterized.RunnerFactory.class)
public class MultiTransactionTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(MultiTransactionTest.class);
    private ZooKeeper zk;
    private ZooKeeper zk_chroot;

    private final boolean useAsync;

    public MultiTransactionTest(boolean useAsync) {
        this.useAsync = useAsync;
    }

    @Parameters
    public static Collection<Object[]> configs() {
        return Arrays.asList(new Object[][] {
            { false }, { true },
        });
    }

    @Before
    public void setUp() throws Exception {
        SyncRequestProcessor.setSnapCount(150);
        super.setUp();
        zk = createClient();
    }

    static class MultiResult {
        int rc;
        List<OpResult> results;
        boolean finished = false;
    }

    private List<OpResult> multi(ZooKeeper zk, Iterable<Op> ops)
    throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          List<OpResult> opResults) {
                    synchronized (res) {
                        res.rc = rc;
                        res.results = opResults;
                        res.finished = true;
                        res.notifyAll();
                    }
                }
            }, null);
            synchronized (res) {
                while (!res.finished) {
                    res.wait();
                }
            }
            if (KeeperException.Code.OK.intValue() != res.rc) {
                KeeperException ke = KeeperException.create(KeeperException.Code.get(res.rc));
                throw ke;
            }
            return res.results;
        } else {
            return zk.multi(ops);
        }
    }

    private void multiHavingErrors(ZooKeeper zk, Iterable<Op> ops,
            List<Integer> expectedResultCodes, String expectedErr)
            throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                        List<OpResult> opResults) {
                    synchronized (res) {
                        res.rc = rc;
                        res.results = opResults;
                        res.finished = true;
                        res.notifyAll();
                    }
                }
            }, null);
            synchronized (res) {
                while (!res.finished) {
                    res.wait();
                }
            }
            for (int i = 0; i < res.results.size(); i++) {
                OpResult opResult = res.results.get(i);
                Assert.assertTrue("Did't receive proper error response",
                        opResult instanceof ErrorResult);
                ErrorResult errRes = (ErrorResult) opResult;
                Assert.assertEquals("Did't receive proper error code",
                        expectedResultCodes.get(i).intValue(), errRes.getErr());
            }
        } else {
            try {
                zk.multi(ops);
                Assert.fail("Shouldn't have validated in ZooKeeper client!");
            } catch (KeeperException e) {
                Assert.assertEquals("Wrong exception", expectedErr, e.code()
                        .name());
            } catch (IllegalArgumentException e) {
                Assert.assertEquals("Wrong exception", expectedErr,
                        e.getMessage());
            }
        }
    }

    private List<OpResult> commit(Transaction txn)
    throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            txn.commit(new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          List<OpResult> opResults) {
                    synchronized (res) {
                        res.rc = rc;
                        res.results = opResults;
                        res.finished = true;
                        res.notifyAll();
                    }
                }
            }, null);
            synchronized (res) {
                while (!res.finished) {
                    res.wait();
                }
            }
            if (KeeperException.Code.OK.intValue() != res.rc) {
                KeeperException ke = KeeperException.create(KeeperException.Code.get(res.rc));
                throw ke;
            }
            return res.results;
        } else {
            return txn.commit();
        }
    }

    /**
     * Test verifies the multi calls with invalid znode path
     */
    @Test(timeout = 90000)
    public void testInvalidPath() throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());
        // create with CreateMode
        List<Op> opList = Arrays.asList(Op.create("/multi0", new byte[0],
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT), Op.create(
                "/multi1/", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT), Op.create("/multi2", new byte[0],
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        String expectedErr = "Path must not end with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);

        // create with valid sequential flag
        opList = Arrays.asList(Op.create("/multi0", new byte[0],
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT), Op.create(
                "multi1/", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL.toFlag()), Op.create("/multi2",
                new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        expectedErr = "Path must start with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);

        // check
        opList = Arrays.asList(Op.check("/multi0", -1),
                Op.check("/multi1/", 100), Op.check("/multi2", 5));
        expectedErr = "Path must not end with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);

        // delete
        opList = Arrays.asList(Op.delete("/multi0", -1),
                Op.delete("/multi1/", 100), Op.delete("/multi2", 5));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);

        // Multiple bad arguments
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());

        // setdata
        opList = Arrays.asList(Op.setData("/multi0", new byte[0], -1),
                Op.setData("/multi1/", new byte[0], -1),
                Op.setData("/multi2", new byte[0], -1),
                Op.setData("multi3", new byte[0], -1));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);
    }

    /**
     * ZOOKEEPER-2052:
     * Multi abort shouldn't have any side effect.
     * We fix a bug in rollback and the following scenario should work:
     * 1. multi delete abort because of not empty directory
     * 2. ephemeral nodes under that directory are deleted
     * 3. multi delete should succeed.
     */
    @Test
    public void testMultiRollback() throws Exception {
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        ZooKeeper epheZk = createClient();
        epheZk.create("/foo/bar", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        List<Op> opList = Arrays.asList(Op.delete("/foo", -1));
        try {
            zk.multi(opList);
            Assert.fail("multi delete should failed for not empty directory");
        } catch (KeeperException.NotEmptyException e) {
        }

        final CountDownLatch latch = new CountDownLatch(1);

        zk.exists("/foo/bar", new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == Event.EventType.NodeDeleted){
                    latch.countDown();
                }
            }
        });

        epheZk.close();

        latch.await();

        try {
            zk.getData("/foo/bar", false, null);
            Assert.fail("ephemeral node should have been deleted");
        } catch (KeeperException.NoNodeException e) {
        }

        zk.multi(opList);

        try {
            zk.getData("/foo", false, null);
            Assert.fail("persistent node should have been deleted after multi");
        } catch (KeeperException.NoNodeException e) {
        }
    }

    /**
     * Test verifies the multi calls with blank znode path
     */
    @Test(timeout = 90000)
    public void testBlankPath() throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());

        // delete
        String expectedErr = "Path cannot be null";
        List<Op> opList = Arrays.asList(Op.delete("/multi0", -1),
                Op.delete(null, 100), Op.delete("/multi2", 5),
                Op.delete("", -1));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);
    }

    /**
     * Test verifies the multi.create with invalid createModeFlag
     */
    @Test(timeout = 90000)
    public void testInvalidCreateModeFlag() throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY
                .intValue());

        int createModeFlag = 6789;
        List<Op> opList = Arrays.asList(Op.create("/multi0", new byte[0],
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT), Op.create(
                "/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, createModeFlag),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT));
        String expectedErr = KeeperException.Code.BADARGUMENTS.name();
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr);
    }

    @Test
    public void testChRootCreateDelete() throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace();
        // Creating child using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        Op createChild = Op.create("/myid", new byte[0],
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
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

    @Test
    public void testChRootSetData() throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace();
        // setData using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        multi(zk_chroot, ops) ;

        for (int i = 0; i < names.length; i++) {
            Assert.assertArrayEquals("zNode data not matching", names[i]
                    .getBytes(), zk_chroot.getData(names[i], false, null));
        }
    }

    @Test
    public void testChRootCheck() throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace();
        // checking the child version using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            zk.create(chRoot + names[i], new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        }
        for (int i = 0; i < names.length; i++) {
            ops.add(Op.check(names[i], 0));
        }
        multi(zk_chroot, ops) ;
    }

    @Test
    public void testChRootTransaction() throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace();
        // checking the child version using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String childPath = "/myid";
        Transaction transaction = zk_chroot.transaction();
        transaction.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        transaction.check(childPath, 0);
        transaction.setData(childPath, childPath.getBytes(), 0);
        commit(transaction);

        Assert.assertNotNull("zNode is not created under chroot:" + chRoot, zk
                .exists(chRoot + childPath, false));
        Assert.assertNotNull("zNode is not created under chroot:" + chRoot,
                zk_chroot.exists(childPath, false));
        Assert.assertNull("zNode is created directly under '/', ignored configured chroot",
                        zk.exists(childPath, false));
        Assert.assertArrayEquals("zNode data not matching", childPath
                .getBytes(), zk_chroot.getData(childPath, false, null));

        transaction = zk_chroot.transaction();
        // Deleting child using chRoot client.
        transaction.delete(childPath, 1);
        commit(transaction);

        Assert.assertNull("chroot:" + chRoot + " exists after delete", zk
                .exists(chRoot + "/myid", false));
        Assert.assertNull("chroot:" + chRoot + " exists after delete",
                zk_chroot.exists("/myid", false));
    }

    private String createNameSpace() throws InterruptedException,
            KeeperException {
        // creating the subtree for chRoot clients.
        String chRoot = "/appsX";
        Op createChRoot = Op.create(chRoot, new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        multi(zk, Arrays.asList(createChRoot));
        return chRoot;
    }


    @Test
    public void testCreate() throws Exception {
        multi(zk, Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                ));
        zk.getData("/multi0", false, null);
        zk.getData("/multi1", false, null);
        zk.getData("/multi2", false, null);
    }

    @Test
    public void testCreateDelete() throws Exception {

        multi(zk, Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0)
                ));

        // '/multi' should have been deleted
        Assert.assertNull(zk.exists("/multi", null));
    }

    @Test
    public void testInvalidVersion() throws Exception {

        try {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 1)
            ));
            Assert.fail("delete /multi should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }
    }

    @Test
    public void testNestedCreate() throws Exception {

        multi(zk, Arrays.asList(
                /* Create */
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a/1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),

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

    @Test
    public void testSetData() throws Exception {

        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        multi(zk, ops) ;

        for (int i = 0; i < names.length; i++) {
            Assert.assertArrayEquals(names[i].getBytes(), zk.getData(names[i], false, null));
        }
    }

    @Test
    public void testUpdateConflict() throws Exception {

        Assert.assertNull(zk.exists("/multi", null));

        try {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.setData("/multi", "X".getBytes(), 0),
                    Op.setData("/multi", "Y".getBytes(), 0)
                    ));
            Assert.fail("Should have thrown a KeeperException for invalid version");
        } catch (KeeperException e) {
            //PASS
            LOG.error("STACKTRACE: ", e);
        }

        Assert.assertNull(zk.exists("/multi", null));

        //Updating version solves conflict -- order matters
        multi(zk, Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.setData("/multi", "X".getBytes(), 0),
                Op.setData("/multi", "Y".getBytes(), 1)
                ));

        Assert.assertArrayEquals(zk.getData("/multi", false, null), "Y".getBytes());
    }

    @Test
    public void testDeleteUpdateConflict() throws Exception {

        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            multi(zk, Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0),
                Op.setData("/multi", "Y".getBytes(), 0)
                ));
            Assert.fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }

        // '/multi' should never have been created as entire op should fail
        Assert.assertNull(zk.exists("/multi", null)) ;
    }

    @Test
    public void testGetResults() throws Exception {
        /* Delete of a node folowed by an update of the (now) deleted node */
        Iterable<Op> ops = Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0),
                Op.setData("/multi", "Y".getBytes(), 0),
                Op.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
        );
        List<OpResult> results = null;
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          List<OpResult> opResults) {
                    synchronized (res) {
                        res.rc = rc;
                        res.results = opResults;
                        res.finished = true;
                        res.notifyAll();
                    }
                }
            }, null);
            synchronized (res) {
                while (!res.finished) {
                    res.wait();
                }
            }
            Assert.assertFalse("/multi should have been deleted so setData should have failed",
                               KeeperException.Code.OK.intValue() == res.rc);
            Assert.assertNull(zk.exists("/multi", null));
            results = res.results;
        } else {
            try {
                zk.multi(ops);
                Assert.fail("/multi should have been deleted so setData should have failed");
            } catch (KeeperException e) {
                // '/multi' should never have been created as entire op should fail
                Assert.assertNull(zk.exists("/multi", null));
                results = e.getResults();
            }
        }

        Assert.assertNotNull(results);
        for (OpResult r : results) {
            LOG.info("RESULT==> {}", r);
            if (r instanceof ErrorResult) {
                ErrorResult er = (ErrorResult) r;
                LOG.info("ERROR RESULT: {} ERR=>{}", er, KeeperException.Code.get(er.getErr()));
            }
        }
    }

    /**
     * Exercise the equals methods of OpResult classes.
     */
    @Test
    public void testOpResultEquals() {
        opEquals(new CreateResult("/foo"),
                 new CreateResult("/foo"),
                 new CreateResult("nope"));

        opEquals(new CreateResult("/foo"),
                 new CreateResult("/foo"),
                 new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));

        opEquals(new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new CreateResult("nope", new Stat(11, 12, 13, 14, 15, 16, 17, 18, 19, 110, 111)));

        opEquals(new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new CreateResult("/foo"));

        opEquals(new CheckResult(),
                 new CheckResult(),
                 null);

        opEquals(new SetDataResult(new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new SetDataResult(new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
                 new SetDataResult(new Stat(11, 12, 13, 14, 15, 16, 17, 18, 19, 110, 111)));
        
        opEquals(new ErrorResult(1),
                 new ErrorResult(1),
                 new ErrorResult(2));
        
        opEquals(new DeleteResult(),
                 new DeleteResult(),
                 null);

        opEquals(new ErrorResult(1),
                 new ErrorResult(1),
                 new ErrorResult(2));
    }

    private void opEquals(OpResult expected, OpResult value, OpResult near) {
        assertEquals(value, value);
        assertFalse(value.equals(new Object()));
        assertFalse(value.equals(near));
        assertFalse(value.equals(value instanceof CreateResult ?
                new ErrorResult(1) : new CreateResult("nope2")));
        assertTrue(value.equals(expected));
    }

    @Test
    public void testWatchesTriggered() throws KeeperException, InterruptedException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        multi(zk, Arrays.asList(
                Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/t", -1)
        ));
        assertTrue(watcher.triggered.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testNoWatchesTriggeredForFailedMultiRequest() throws InterruptedException, KeeperException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        try {
            multi(zk, Arrays.asList(
                    Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/nonexisting", -1)
            ));
            fail("expected previous multi op to fail!");
        } catch (KeeperException.NoNodeException e) {
            // expected
        }
        SyncCallback cb = new SyncCallback();
        zk.sync("/", cb, null);

        // by waiting for the callback we're assured that the event queue is flushed
        cb.done.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
        assertEquals(1, watcher.triggered.getCount());
    }
    
    @Test
    public void testTransactionBuilder() throws Exception {
        List<OpResult> results = commit(zk.transaction()
                .create("/t1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                .create("/t1/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                .create("/t2", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        assertEquals(3, results.size());
        for (OpResult r : results) {
            CreateResult c = (CreateResult)r;
            assertTrue(c.getPath().startsWith("/t"));
            assertNotNull(c.toString());
        }
        assertNotNull(zk.exists("/t1", false));
        assertNotNull(zk.exists("/t1/child", false));
        assertNotNull(zk.exists("/t2", false));
        
        results = commit(zk.transaction()
                .check("/t1", 0)
                .check("/t1/child", 0)
                .check("/t2", 0));
        assertEquals(3, results.size());
        for (OpResult r : results) {
            CheckResult c = (CheckResult)r;
            assertNotNull(c.toString());
        }
        
        try {
            results = commit(zk.transaction()
                    .check("/t1", 0)
                    .check("/t1/child", 0)
                    .check("/t2", 1));
            fail();
        } catch (KeeperException.BadVersionException e) {
            // expected
        }
        
        results = commit(zk.transaction()
                .check("/t1", 0)
                .setData("/t1", new byte[0], 0));
        assertEquals(2, results.size());
        for (OpResult r : results) {
            assertNotNull(r.toString());
        }

        try {
            results = commit(zk.transaction()
                    .check("/t1", 1)
                    .setData("/t1", new byte[0], 2));
            fail();
        } catch (KeeperException.BadVersionException e) {
            // expected
        }
        
        results = commit(zk.transaction()
                .check("/t1", 1)
                .check("/t1/child", 0)
                .check("/t2", 0));
        assertEquals(3, results.size());

        results = commit(zk.transaction()
                .delete("/t2", -1)
                .delete("/t1/child", -1));
        assertEquals(2, results.size());
        for (OpResult r : results) {
            DeleteResult d = (DeleteResult)r;
            assertNotNull(d.toString());
        }
        assertNotNull(zk.exists("/t1", false));
        assertNull(zk.exists("/t1/child", false));
        assertNull(zk.exists("/t2", false));
    }

    private static class HasTriggeredWatcher implements Watcher {
        private final CountDownLatch triggered = new CountDownLatch(1);

        @Override
        public void process(WatchedEvent event) {
            triggered.countDown();
        }
    }
    private static class SyncCallback implements AsyncCallback.VoidCallback {
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void processResult(int rc, String path, Object ctx) {
            done.countDown();
        }
    }
}
