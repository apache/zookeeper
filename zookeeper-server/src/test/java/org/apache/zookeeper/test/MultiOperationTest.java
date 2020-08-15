/*
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.AsyncCallback.MultiCallback;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.OpResult.CheckResult;
import org.apache.zookeeper.OpResult.CreateResult;
import org.apache.zookeeper.OpResult.DeleteResult;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.OpResult.SetDataResult;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiOperationTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(MultiOperationTest.class);
    private ZooKeeper zk;
    private ZooKeeper zk_chroot;

    @BeforeEach
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

    private List<OpResult> multi(ZooKeeper zk, Iterable<Op> ops, boolean useAsync) throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, List<OpResult> opResults) {
                    if (!ClientCnxn.isInEventThread()) {
                        throw new RuntimeException("not in event thread");
                    }
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
            // In case of only OpKind.READ operations, no exception is thrown. Errors only marked in form of ErrorResults.
            if (KeeperException.Code.OK.intValue() != res.rc && ops.iterator().next().getKind() != Op.OpKind.READ) {
                KeeperException ke = KeeperException.create(KeeperException.Code.get(res.rc));
                throw ke;
            }
            return res.results;
        } else {
            return zk.multi(ops);
        }
    }

    private void multiHavingErrors(ZooKeeper zk, Iterable<Op> ops, List<Integer> expectedResultCodes, String expectedErr, boolean useAsync) throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, List<OpResult> opResults) {
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
                assertTrue(opResult instanceof ErrorResult, "Did't receive proper error response");
                ErrorResult errRes = (ErrorResult) opResult;
                assertEquals(expectedResultCodes.get(i).intValue(), errRes.getErr(), "Did't receive proper error code");
            }
        } else {
            try {
                zk.multi(ops);
                fail("Shouldn't have validated in ZooKeeper client!");
            } catch (KeeperException e) {
                assertEquals(expectedErr, e.code().name(), "Wrong exception");
            } catch (IllegalArgumentException e) {
                assertEquals(expectedErr, e.getMessage(), "Wrong exception");
            }
        }
    }

    private List<OpResult> commit(Transaction txn, boolean useAsync) throws KeeperException, InterruptedException {
        if (useAsync) {
            final MultiResult res = new MultiResult();
            txn.commit(new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, List<OpResult> opResults) {
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
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(value = 90)
    public void testInvalidPath(boolean useAsync) throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
        // create with CreateMode
        List<Op> opList = Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1/", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        String expectedErr = "Path must not end with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);

        // create with valid sequential flag
        opList = Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("multi1/", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL.toFlag()),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        expectedErr = "Path must start with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);

        // check
        opList = Arrays.asList(
                Op.check("/multi0", -1), Op.check("/multi1/", 100),
                Op.check("/multi2", 5));
        expectedErr = "Path must not end with / character";
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);

        // delete
        opList = Arrays.asList(
                Op.delete("/multi0", -1),
                Op.delete("/multi1/", 100),
                Op.delete("/multi2", 5));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);

        // Multiple bad arguments
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());

        // setdata
        opList = Arrays.asList(
                Op.setData("/multi0", new byte[0], -1),
                Op.setData("/multi1/", new byte[0], -1),
                Op.setData("/multi2", new byte[0], -1),
                Op.setData("multi3", new byte[0], -1));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);
    }

    /**
     * ZOOKEEPER-2052:
     * Multi abort shouldn't have any side effect.
     * We fix a bug in rollback and the following scenario should work:
     * 1. multi delete abort because of not empty directory
     * 2. ephemeral nodes under that directory are deleted
     * 3. multi delete should succeed.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiRollback(boolean useAsync) throws Exception {
        zk.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        ZooKeeper epheZk = createClient();
        epheZk.create("/foo/bar", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        List<Op> opList = Arrays.asList(Op.delete("/foo", -1));
        try {
            zk.multi(opList);
            fail("multi delete should failed for not empty directory");
        } catch (KeeperException.NotEmptyException e) {
        }

        final CountDownLatch latch = new CountDownLatch(1);

        zk.exists("/foo/bar", event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                latch.countDown();
            }
        });

        epheZk.close();

        latch.await();

        try {
            zk.getData("/foo/bar", false, null);
            fail("ephemeral node should have been deleted");
        } catch (KeeperException.NoNodeException e) {
        }

        zk.multi(opList);

        try {
            zk.getData("/foo", false, null);
            fail("persistent node should have been deleted after multi");
        } catch (KeeperException.NoNodeException e) {
        }
    }

    /**
     * Test verifies the multi calls with blank znode path
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(value = 90)
    public void testBlankPath(boolean useAsync) throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());

        // delete
        String expectedErr = "Path cannot be null";
        List<Op> opList = Arrays.asList(
                Op.delete("/multi0", -1),
                Op.delete(null, 100),
                Op.delete("/multi2", 5),
                Op.delete("", -1));
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);
    }

    /**
     * Test verifies the multi.create with invalid createModeFlag
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(value = 90)
    public void testInvalidCreateModeFlag(boolean useAsync) throws Exception {
        List<Integer> expectedResultCodes = new ArrayList<Integer>();
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
        expectedResultCodes.add(KeeperException.Code.BADARGUMENTS.intValue());
        expectedResultCodes.add(KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());

        int createModeFlag = 6789;
        List<Op> opList = Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, createModeFlag),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        String expectedErr = KeeperException.Code.BADARGUMENTS.name();
        multiHavingErrors(zk, opList, expectedResultCodes, expectedErr, useAsync);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testChRootCreateDelete(boolean useAsync) throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace(useAsync);
        // Creating child using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        Op createChild = Op.create("/myid", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        multi(zk_chroot, Arrays.asList(createChild), useAsync);

        assertNotNull(zk.exists(chRoot + "/myid", false), "zNode is not created under chroot:" + chRoot);
        assertNotNull(zk_chroot.exists("/myid", false), "zNode is not created under chroot:" + chRoot);
        assertNull(zk.exists("/myid", false), "zNode is created directly under '/', ignored configured chroot");

        // Deleting child using chRoot client.
        Op deleteChild = Op.delete("/myid", 0);
        multi(zk_chroot, Arrays.asList(deleteChild), useAsync);
        assertNull(zk.exists(chRoot + "/myid", false), "zNode exists under chroot:" + chRoot);
        assertNull(zk_chroot.exists("/myid", false), "zNode exists under chroot:" + chRoot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testChRootSetData(boolean useAsync) throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace(useAsync);
        // setData using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        multi(zk_chroot, ops, useAsync);

        for (int i = 0; i < names.length; i++) {
            assertArrayEquals(names[i].getBytes(), zk_chroot.getData(names[i], false, null), "zNode data not matching");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testChRootCheck(boolean useAsync) throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace(useAsync);
        // checking the child version using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            zk.create(chRoot + names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        for (int i = 0; i < names.length; i++) {
            ops.add(Op.check(names[i], 0));
        }
        multi(zk_chroot, ops, useAsync);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testChRootTransaction(boolean useAsync) throws Exception {
        // creating the subtree for chRoot clients.
        String chRoot = createNameSpace(useAsync);
        // checking the child version using chRoot client.
        zk_chroot = createClient(this.hostPort + chRoot);
        String childPath = "/myid";
        Transaction transaction = zk_chroot.transaction();
        transaction.create(childPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        transaction.check(childPath, 0);
        transaction.setData(childPath, childPath.getBytes(), 0);
        commit(transaction, useAsync);

        assertNotNull(zk.exists(chRoot + childPath, false), "zNode is not created under chroot:" + chRoot);
        assertNotNull(zk_chroot.exists(childPath, false), "zNode is not created under chroot:" + chRoot);
        assertNull(zk.exists(childPath, false), "zNode is created directly under '/', ignored configured chroot");
        assertArrayEquals(childPath.getBytes(), zk_chroot.getData(childPath, false, null), "zNode data not matching");

        transaction = zk_chroot.transaction();
        // Deleting child using chRoot client.
        transaction.delete(childPath, 1);
        commit(transaction, useAsync);

        assertNull(zk.exists(chRoot + "/myid", false), "chroot:" + chRoot + " exists after delete");
        assertNull(zk_chroot.exists("/myid", false), "chroot:" + chRoot + " exists after delete");
    }

    private String createNameSpace(boolean useAsync) throws InterruptedException, KeeperException {
        // creating the subtree for chRoot clients.
        String chRoot = "/appsX";
        Op createChRoot = Op.create(chRoot, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        multi(zk, Arrays.asList(createChRoot), useAsync);
        return chRoot;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCreate(boolean useAsync) throws Exception {
        multi(zk, Arrays.asList(
                Op.create("/multi0", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi2", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)),
                useAsync);
        zk.getData("/multi0", false, null);
        zk.getData("/multi1", false, null);
        zk.getData("/multi2", false, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testEmpty(boolean useAsync) throws Exception {
        multi(zk, Arrays.asList(), useAsync);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCreateDelete(boolean useAsync) throws Exception {
        multi(zk, Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0)),
                useAsync);

        // '/multi' should have been deleted
        assertNull(zk.exists("/multi", null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testInvalidVersion(boolean useAsync) throws Exception {

        try {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 1)),
                    useAsync);
            fail("delete /multi should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testNestedCreate(boolean useAsync) throws Exception {

        multi(zk, Arrays.asList(
                /* Create */
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create("/multi/a/1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                /* Delete */
                Op.delete("/multi/a/1", 0), Op.delete("/multi/a", 0), Op.delete("/multi", 0)), useAsync);

        //Verify tree deleted
        assertNull(zk.exists("/multi/a/1", null));
        assertNull(zk.exists("/multi/a", null));
        assertNull(zk.exists("/multi", null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetData(boolean useAsync) throws Exception {

        String[] names = {"/multi0", "/multi1", "/multi2"};
        List<Op> ops = new ArrayList<Op>();

        for (int i = 0; i < names.length; i++) {
            ops.add(Op.create(names[i], new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.setData(names[i], names[i].getBytes(), 0));
        }

        multi(zk, ops, useAsync);

        for (int i = 0; i < names.length; i++) {
            assertArrayEquals(names[i].getBytes(), zk.getData(names[i], false, null));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testUpdateConflict(boolean useAsync) throws Exception {

        assertNull(zk.exists("/multi", null));

        try {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.setData("/multi", "X".getBytes(), 0),
                    Op.setData("/multi", "Y".getBytes(), 0)),
                    useAsync);
            fail("Should have thrown a KeeperException for invalid version");
        } catch (KeeperException e) {
            //PASS
            LOG.error("STACKTRACE: ", e);
        }

        assertNull(zk.exists("/multi", null));

        //Updating version solves conflict -- order matters
        multi(zk, Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.setData("/multi", "X".getBytes(), 0),
                Op.setData("/multi", "Y".getBytes(), 1)),
                useAsync);

        assertArrayEquals(zk.getData("/multi", false, null), "Y".getBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDeleteUpdateConflict(boolean useAsync) throws Exception {

        /* Delete of a node folowed by an update of the (now) deleted node */
        try {
            multi(zk, Arrays.asList(
                    Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/multi", 0),
                    Op.setData("/multi", "Y".getBytes(), 0)),
                    useAsync);
            fail("/multi should have been deleted so setData should have failed");
        } catch (KeeperException e) {
            /* PASS */
        }

        // '/multi' should never have been created as entire op should fail
        assertNull(zk.exists("/multi", null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGetResults(boolean useAsync) throws Exception {
        /* Delete of a node folowed by an update of the (now) deleted node */
        Iterable<Op> ops = Arrays.asList(
                Op.create("/multi", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/multi", 0),
                Op.setData("/multi", "Y".getBytes(), 0),
                Op.create("/foo", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        List<OpResult> results = null;
        if (useAsync) {
            final MultiResult res = new MultiResult();
            zk.multi(ops, new MultiCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx, List<OpResult> opResults) {
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
            assertFalse(KeeperException.Code.OK.intValue() == res.rc,
                    "/multi should have been deleted so setData should have failed");
            assertNull(zk.exists("/multi", null));
            results = res.results;
        } else {
            try {
                zk.multi(ops);
                fail("/multi should have been deleted so setData should have failed");
            } catch (KeeperException e) {
                // '/multi' should never have been created as entire op should fail
                assertNull(zk.exists("/multi", null));
                results = e.getResults();
            }
        }

        assertNotNull(results);
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
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testOpResultEquals(boolean useAsync) {
        opEquals(new CreateResult("/foo"), new CreateResult("/foo"), new CreateResult("nope"));

        opEquals(new CreateResult("/foo"), new CreateResult("/foo"), new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));

        opEquals(new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new CreateResult("nope", new Stat(11, 12, 13, 14, 15, 16, 17, 18, 19, 110, 111)));

        opEquals(new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new CreateResult("/foo", new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new CreateResult("/foo"));

        opEquals(new CheckResult(), new CheckResult(), null);

        opEquals(new SetDataResult(new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new SetDataResult(new Stat(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), new SetDataResult(new Stat(11, 12, 13, 14, 15, 16, 17, 18, 19, 110, 111)));

        opEquals(new ErrorResult(1), new ErrorResult(1), new ErrorResult(2));

        opEquals(new DeleteResult(), new DeleteResult(), null);

        opEquals(new ErrorResult(1), new ErrorResult(1), new ErrorResult(2));
    }

    private void opEquals(OpResult expected, OpResult value, OpResult near) {
        assertEquals(value, value);
        assertFalse(value.equals(new Object()));
        assertFalse(value.equals(near));
        assertFalse(value.equals(value instanceof CreateResult ? new ErrorResult(1) : new CreateResult("nope2")));
        assertTrue(value.equals(expected));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testWatchesTriggered(boolean useAsync) throws KeeperException, InterruptedException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        multi(zk, Arrays.asList(
                Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.delete("/t", -1)),
                useAsync);
        assertTrue(watcher.triggered.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testNoWatchesTriggeredForFailedMultiRequest(boolean useAsync) throws InterruptedException, KeeperException {
        HasTriggeredWatcher watcher = new HasTriggeredWatcher();
        zk.getChildren("/", watcher);
        try {
            multi(zk, Arrays.asList(
                    Op.create("/t", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                    Op.delete("/nonexisting", -1)),
                    useAsync);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testTransactionBuilder(boolean useAsync) throws Exception {
        List<OpResult> results = commit(
                zk.transaction()
                        .create("/t1", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                        .create("/t1/child", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                        .create("/t2", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL), useAsync);
        assertEquals(3, results.size());
        for (OpResult r : results) {
            CreateResult c = (CreateResult) r;
            assertTrue(c.getPath().startsWith("/t"));
            assertNotNull(c.toString());
        }
        assertNotNull(zk.exists("/t1", false));
        assertNotNull(zk.exists("/t1/child", false));
        assertNotNull(zk.exists("/t2", false));

        results = commit(zk.transaction().check("/t1", 0).check("/t1/child", 0).check("/t2", 0), useAsync);
        assertEquals(3, results.size());
        for (OpResult r : results) {
            CheckResult c = (CheckResult) r;
            assertNotNull(c.toString());
        }

        try {
            results = commit(zk.transaction().check("/t1", 0).check("/t1/child", 0).check("/t2", 1), useAsync);
            fail();
        } catch (KeeperException.BadVersionException e) {
            // expected
        }

        results = commit(zk.transaction().check("/t1", 0).setData("/t1", new byte[0], 0), useAsync);
        assertEquals(2, results.size());
        for (OpResult r : results) {
            assertNotNull(r.toString());
        }

        try {
            results = commit(zk.transaction().check("/t1", 1).setData("/t1", new byte[0], 2), useAsync);
            fail();
        } catch (KeeperException.BadVersionException e) {
            // expected
        }

        results = commit(zk.transaction().check("/t1", 1).check("/t1/child", 0).check("/t2", 0), useAsync);
        assertEquals(3, results.size());

        results = commit(zk.transaction().delete("/t2", -1).delete("/t1/child", -1), useAsync);
        assertEquals(2, results.size());
        for (OpResult r : results) {
            DeleteResult d = (DeleteResult) r;
            assertNotNull(d.toString());
        }
        assertNotNull(zk.exists("/t1", false));
        assertNull(zk.exists("/t1/child", false));
        assertNull(zk.exists("/t2", false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetChildren(boolean useAsync) throws Exception {
        List<String> topLevelNodes = new ArrayList<String>();
        Map<String, List<String>> childrenNodes = new HashMap<String, List<String>>();
        // Creating a database where '/fooX' nodes has 'barXY' named children.
        for (int i = 0; i < 10; i++) {
            String name = "/foo" + i;
            zk.create(name, name.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            topLevelNodes.add(name);
            childrenNodes.put(name, new ArrayList<>());
            for (int j = 0; j < 10; j++) {
                String childname = name + "/bar" + i + j;
                String childname_s = "bar" + i + j;
                zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                childrenNodes.get(name).add(childname_s);
            }
        }
        // Create a multi operation, which queries the children of the nodes in topLevelNodes.
        List<OpResult> multiChildrenList = multi(zk, topLevelNodes.stream().map(Op::getChildren).collect(Collectors.toList()), useAsync);
        for (int i = 0; i < topLevelNodes.size(); i++) {
            String nodeName = topLevelNodes.get(i);
            assertTrue(multiChildrenList.get(i) instanceof OpResult.GetChildrenResult);
            List<String> childrenList = ((OpResult.GetChildrenResult) multiChildrenList.get(i)).getChildren();
            // In general, we do not demand an order from the children list but to contain every child.
            assertEquals(new TreeSet<String>(childrenList), new TreeSet<String>(childrenNodes.get(nodeName)));

            List<String> children = zk.getChildren(nodeName, false);
            assertEquals(childrenList, children);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetChildrenSameNode(boolean useAsync) throws Exception {
        List<String> childrenNodes = new ArrayList<String>();
        // Creating a database where '/foo' node has 'barX' named children.
        String topLevelNode = "/foo";
        zk.create(topLevelNode, topLevelNode.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        for (int i = 0; i < 10; i++) {
            String childname = topLevelNode + "/bar" + i;
            String childname_s = "bar" + i;
            zk.create(childname, childname.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            childrenNodes.add(childname_s);
        }

        // Check for getting the children of the same node twice.
        List<OpResult> sameChildrenList = multi(zk, Arrays.asList(
                Op.getChildren(topLevelNode),
                Op.getChildren(topLevelNode)),
                useAsync);
        // The response should contain two elements which are the same.
        assertEquals(sameChildrenList.size(), 2);
        assertEquals(sameChildrenList.get(0), sameChildrenList.get(1));
        // Check the actual result.
        assertTrue(sameChildrenList.get(0) instanceof OpResult.GetChildrenResult);
        OpResult.GetChildrenResult gcr = (OpResult.GetChildrenResult) sameChildrenList.get(0);
        // In general, we do not demand an order from the children list but to contain every child.
        assertEquals(new TreeSet<String>(gcr.getChildren()), new TreeSet<String>(childrenNodes));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetChildrenAuthentication(boolean useAsync) throws KeeperException, InterruptedException {
        List<ACL> writeOnly = Collections.singletonList(new ACL(ZooDefs.Perms.WRITE, new Id("world", "anyone")));
        zk.create("/foo_auth", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_auth/bar", null, Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_no_auth", null, writeOnly, CreateMode.PERSISTENT);

        // Check for normal behaviour.
        List<OpResult> multiChildrenList = multi(zk, Arrays.asList(Op.getChildren("/foo_auth")), useAsync);
        assertEquals(multiChildrenList.size(), 1);
        assertTrue(multiChildrenList.get(0) instanceof OpResult.GetChildrenResult);
        List<String> childrenList = ((OpResult.GetChildrenResult) multiChildrenList.get(0)).getChildren();
        assertEquals(childrenList.size(), 1);
        assertEquals(childrenList.get(0), "bar");

        // Check for authentication violation.
        multiChildrenList = multi(zk, Arrays.asList(Op.getChildren("/foo_no_auth")), useAsync);

        assertEquals(multiChildrenList.size(), 1);
        assertTrue(multiChildrenList.get(0) instanceof OpResult.ErrorResult);
        assertEquals(((OpResult.ErrorResult) multiChildrenList.get(0)).getErr(), KeeperException.Code.NOAUTH.intValue(),
                "Expected NoAuthException for getting the children of a write only node");

    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetChildrenMixedAuthenticationErrorFirst(boolean useAsync) throws KeeperException, InterruptedException {
        List<ACL> writeOnly = Collections.singletonList(new ACL(ZooDefs.Perms.WRITE, new Id("world", "anyone")));
        zk.create("/foo_auth", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_auth/bar", null, Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_no_auth", null, writeOnly, CreateMode.PERSISTENT);
        List<OpResult> multiChildrenList;

        // Mixed nodes, the operation after the error should return RuntimeInconsistency error.
        multiChildrenList = multi(zk, Arrays.asList(Op.getChildren("/foo_no_auth"), Op.getChildren("/foo_auth")), useAsync);

        assertEquals(multiChildrenList.size(), 2);
        assertTrue(multiChildrenList.get(0) instanceof OpResult.ErrorResult);
        assertEquals(((OpResult.ErrorResult) multiChildrenList.get(0)).getErr(), KeeperException.Code.NOAUTH.intValue(),
                "Expected NoAuthException for getting the children of a write only node");

        assertTrue(multiChildrenList.get(1) instanceof OpResult.GetChildrenResult);
        List<String> childrenList = ((OpResult.GetChildrenResult) multiChildrenList.get(1)).getChildren();
        assertEquals(childrenList.size(), 1);
        assertEquals(childrenList.get(0), "bar");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetChildrenMixedAuthenticationCorrectFirst(boolean useAsync) throws KeeperException, InterruptedException {
        List<ACL> writeOnly = Collections.singletonList(new ACL(ZooDefs.Perms.WRITE, new Id("world", "anyone")));
        zk.create("/foo_auth", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_auth/bar", null, Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/foo_no_auth", null, writeOnly, CreateMode.PERSISTENT);

        // Check for getting the children of the nodes with mixed authentication.
        // The getChildren operation returns GetChildrenResult if it happened before the error.
        List<OpResult> multiChildrenList;
        multiChildrenList = multi(zk, Arrays.asList(Op.getChildren("/foo_auth"), Op.getChildren("/foo_no_auth")), useAsync);
        assertSame(multiChildrenList.size(), 2);

        assertTrue(multiChildrenList.get(0) instanceof OpResult.GetChildrenResult);
        List<String> childrenList = ((OpResult.GetChildrenResult) multiChildrenList.get(0)).getChildren();
        assertEquals(childrenList.size(), 1);
        assertEquals(childrenList.get(0), "bar");

        assertTrue(multiChildrenList.get(1) instanceof OpResult.ErrorResult);
        assertEquals(((OpResult.ErrorResult) multiChildrenList.get(1)).getErr(), KeeperException.Code.NOAUTH.intValue(),
                "Expected NoAuthException for getting the children of a write only node");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiGetData(boolean useAsync) throws Exception {
        zk.create("/node1", "data1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/node2", "data2".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        List<OpResult> multiData = multi(zk, Arrays.asList(Op.getData("/node1"), Op.getData("/node2")), useAsync);
        assertEquals(multiData.size(), 2);
        assertArrayEquals(((OpResult.GetDataResult) multiData.get(0)).getData(), "data1".getBytes());
        assertArrayEquals(((OpResult.GetDataResult) multiData.get(1)).getData(), "data2".getBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiRead(boolean useAsync) throws Exception {
        zk.create("/node1", "data1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/node2", "data2".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        zk.create("/node1/node1", "data11".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.create("/node1/node2", "data12".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        List<OpResult> multiRead = multi(zk, Arrays.asList(
                Op.getChildren("/node1"),
                Op.getData("/node1"),
                Op.getChildren("/node2"),
                Op.getData("/node2")),
                useAsync);
        assertEquals(multiRead.size(), 4);
        assertTrue(multiRead.get(0) instanceof OpResult.GetChildrenResult);
        List<String> childrenList = ((OpResult.GetChildrenResult) multiRead.get(0)).getChildren();
        assertEquals(childrenList.size(), 2);
        assertEquals(new TreeSet<String>(childrenList), new TreeSet<String>(Arrays.asList("node1", "node2")));

        assertArrayEquals(((OpResult.GetDataResult) multiRead.get(1)).getData(), "data1".getBytes());
        Stat stat = ((OpResult.GetDataResult) multiRead.get(1)).getStat();
        assertEquals(stat.getMzxid(), stat.getCzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(2, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(5, stat.getDataLength());
        assertEquals(2, stat.getNumChildren());

        assertTrue(multiRead.get(2) instanceof OpResult.GetChildrenResult);
        childrenList = ((OpResult.GetChildrenResult) multiRead.get(2)).getChildren();
        assertTrue(childrenList.isEmpty());

        assertArrayEquals(((OpResult.GetDataResult) multiRead.get(3)).getData(), "data2".getBytes());
        stat = ((OpResult.GetDataResult) multiRead.get(3)).getStat();
        assertEquals(stat.getMzxid(), stat.getCzxid());
        assertEquals(stat.getMzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(zk.getSessionId(), stat.getEphemeralOwner());
        assertEquals(5, stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMixedReadAndTransaction(boolean useAsync) throws Exception {
        zk.create("/node", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        try {
            List<OpResult> multiRead = multi(zk, Arrays.asList(
                    Op.setData("/node1", "data1".getBytes(), -1),
                    Op.getData("/node1")),
                    useAsync);
            fail("Mixed kind of operations are not allowed");
        } catch (IllegalArgumentException e) {
            // expected
        }
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
