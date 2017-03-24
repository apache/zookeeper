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

package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.data.PathWithStat;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.zookeeper.Quotas;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.common.PathTrie;
import java.lang.reflect.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataTreeTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(DataTreeTest.class);

    private DataTree dt;

    @Before
    public void setUp() throws Exception {
        dt=new DataTree();
    }

    @After
    public void tearDown() throws Exception {
        dt=null;
    }

    /**
     * For ZOOKEEPER-1755 - Test race condition when taking dumpEphemerals and
     * removing the session related ephemerals from DataTree structure
     */
    @Test(timeout = 60000)
    public void testDumpEphemerals() throws Exception {
        int count = 1000;
        long session = 1000;
        long zxid = 2000;
        final DataTree dataTree = new DataTree();
        LOG.info("Create {} zkclient sessions and its ephemeral nodes", count);
        createEphemeralNode(session, dataTree, count);
        final AtomicBoolean exceptionDuringDumpEphemerals = new AtomicBoolean(
                false);
        final AtomicBoolean running = new AtomicBoolean(true);
        Thread thread = new Thread() {
            public void run() {
                PrintWriter pwriter = new PrintWriter(new StringWriter());
                try {
                    while (running.get()) {
                        dataTree.dumpEphemerals(pwriter);
                    }
                } catch (Exception e) {
                    LOG.error("Received exception while dumpEphemerals!", e);
                    exceptionDuringDumpEphemerals.set(true);
                }
            };
        };
        thread.start();
        LOG.debug("Killing {} zkclient sessions and its ephemeral nodes", count);
        killZkClientSession(session, zxid, dataTree, count);
        running.set(false);
        thread.join();
        Assert.assertFalse("Should have got exception while dumpEphemerals!",
                exceptionDuringDumpEphemerals.get());
    }

    private void killZkClientSession(long session, long zxid,
            final DataTree dataTree, int count) {
        for (int i = 0; i < count; i++) {
            dataTree.killSession(session + i, zxid);
        }
    }

    private void createEphemeralNode(long session, final DataTree dataTree,
            int count) throws NoNodeException, NodeExistsException {
        for (int i = 0; i < count; i++) {
            dataTree.createNode("/test" + i, new byte[0], null, session + i,
                    dataTree.getNode("/").stat.getCversion() + 1, 1, 1);
        }
    }

    @Test(timeout = 60000)
    public void testRootWatchTriggered() throws Exception {
        class MyWatcher implements Watcher{
            boolean fired=false;
            public void process(WatchedEvent event) {
                if(event.getPath().equals("/"))
                    fired=true;
            }
        }
        MyWatcher watcher=new MyWatcher();
        // set a watch on the root node
        dt.getChildren("/", new Stat(), watcher);
        // add a new node, should trigger a watch
        dt.createNode("/xyz", new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);
        Assert.assertFalse("Root node watch not triggered",!watcher.fired);
    }

    /**
     * For ZOOKEEPER-1046 test if cversion is getting incremented correctly.
     */
    @Test(timeout = 60000)
    public void testIncrementCversion() throws Exception {
        dt.createNode("/test", new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);
        DataNode zk = dt.getNode("/test");
        int prevCversion = zk.stat.getCversion();
        long prevPzxid = zk.stat.getPzxid();
        dt.setCversionPzxid("/test/",  prevCversion + 1, prevPzxid + 1);
        int newCversion = zk.stat.getCversion();
        long newPzxid = zk.stat.getPzxid();
        Assert.assertTrue("<cversion, pzxid> verification failed. Expected: <" +
                (prevCversion + 1) + ", " + (prevPzxid + 1) + ">, found: <" +
                newCversion + ", " + newPzxid + ">",
                (newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1));
    }

    @Test(timeout = 60000)
    public void testPathTrieClearOnDeserialize() throws Exception {

        //Create a DataTree with quota nodes so PathTrie get updated
        DataTree dserTree = new DataTree();

        dserTree.createNode("/bug", new byte[20], null, -1, 1, 1, 1);
        dserTree.createNode(Quotas.quotaZookeeper+"/bug", null, null, -1, 1, 1, 1);
        dserTree.createNode(Quotas.quotaPath("/bug"), new byte[20], null, -1, 1, 1, 1);
        dserTree.createNode(Quotas.statPath("/bug"), new byte[20], null, -1, 1, 1, 1);

        //deserialize a DataTree; this should clear the old /bug nodes and pathTrie
        DataTree tree = new DataTree();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        tree.serialize(oa, "test");
        baos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);
        dserTree.deserialize(ia, "test");

        Field pfield = DataTree.class.getDeclaredField("pTrie");
        pfield.setAccessible(true);
        PathTrie pTrie = (PathTrie)pfield.get(dserTree);

        //Check that the node path is removed from pTrie
        Assert.assertEquals("/bug is still in pTrie", "", pTrie.findMaxPrefix("/bug"));
    }

    /*
     * ZOOKEEPER-2201 - OutputArchive.writeRecord can block for long periods of
     * time, we must call it outside of the node lock.
     * We call tree.serialize, which calls our modified writeRecord method that
     * blocks until it can verify that a separate thread can lock the DataNode
     * currently being written, i.e. that DataTree.serializeNode does not hold
     * the DataNode lock while calling OutputArchive.writeRecord.
     */
    @Test(timeout = 60000)
    public void testSerializeDoesntLockDataNodeWhileWriting() throws Exception {
        DataTree tree = new DataTree();
        tree.createNode("/marker", new byte[] {42}, null, -1, 1, 1, 1);
        final DataNode markerNode = tree.getNode("/marker");
        final AtomicBoolean ranTestCase = new AtomicBoolean();
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());
        BinaryOutputArchive oa = new BinaryOutputArchive(out) {
            @Override
            public void writeRecord(Record r, String tag) throws IOException {
                // Need check if the record is a DataNode instance because of changes in ZOOKEEPER-2014
                // which adds default ACL to config node.
                if (r instanceof DataNode) {
                    DataNode node = (DataNode) r;
                    if (node.data.length == 1 && node.data[0] == 42) {
                        final Semaphore semaphore = new Semaphore(0);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (markerNode) {
                                    //When we lock markerNode, allow writeRecord to continue
                                    semaphore.release();
                                }
                            }
                        }).start();

                        try {
                            boolean acquired = semaphore.tryAcquire(30, TimeUnit.SECONDS);
                            //This is the real assertion - could another thread lock
                            //the DataNode we're currently writing
                            Assert.assertTrue("Couldn't acquire a lock on the DataNode while we were calling tree.serialize", acquired);
                        } catch (InterruptedException e1) {
                            throw new RuntimeException(e1);
                        }
                        ranTestCase.set(true);
                    }
                }

                super.writeRecord(r, tag);
            }
        };

        tree.serialize(oa, "test");

        //Let's make sure that we hit the code that ran the real assertion above
        Assert.assertTrue("Didn't find the expected node", ranTestCase.get());
    }

    @Test(timeout = 60000)
    public void getChildrenPaginated() throws NodeExistsException, NoNodeException {
        final String rootPath   = "/children";
        final int firstCzxId    = 1000;
        final int countNodes    = 10;

        //  Create the parent node
        dt.createNode(rootPath, new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);

        //  Create 10 child nodes
        for (int i = 0; i < countNodes; ++i) {
            dt.createNode(rootPath + "/test-" + i, new byte[0], null, 0, dt.getNode(rootPath).stat.getCversion() + i + 1, firstCzxId + i, 1);
        }

        //  Asking from a negative for 5 nodes should return the 5, and not set the watch
        int curWatchCount = dt.getWatchCount();
        List<PathWithStat> result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 5, -1, 0);
        Assert.assertEquals(5, result.size());
        Assert.assertEquals("The watch not should have been set", curWatchCount, dt.getWatchCount());
        //  Verify that the list is sorted
        String before = "";
        for (final PathWithStat s: result) {
            final String path = s.getPath();
            Assert.assertTrue(String.format("The next path (%s) should be > previons (%s)", path, before),
                    path.compareTo(before) > 0);
            before = path;
        }

        //  Asking from a negative would give me all children, and set the watch
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), countNodes, -1, 0);
        Assert.assertEquals(countNodes, result.size());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());
        //  Verify that the list is sorted
        before = "";
        for (final PathWithStat s: result) {
            final String path = s.getPath();
            Assert.assertTrue(String.format("The next path (%s) should be > previons (%s)", path, before),
                    path.compareTo(before) > 0);
            before = path;
        }

        //  Asking from the last one should return only onde node
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 2, 1000 + countNodes - 1, 0);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("test-" + (countNodes - 1), result.get(0).getPath());
        Assert.assertEquals(firstCzxId + countNodes - 1, result.get(0).getStat().getMzxid());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());

        //  Asking from the last created node+1 should return an empty list and set the watch
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 2, 1000 + countNodes, 0);
        Assert.assertTrue("The result should be an empty list", result.isEmpty());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());

        //  Asking from -1 for one node should return two, and NOT set the watch
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 1, -1, 0);
        Assert.assertEquals("No watch should be set", curWatchCount, dt.getWatchCount());
        Assert.assertEquals("We only return up to ", 1, result.size());
        //  Check that we ordered correctly
        Assert.assertEquals("test-0", result.get(0).getPath());
    }

    @Test(timeout = 60000)
    public void getChildrenPaginatedWithOffset() throws NodeExistsException, NoNodeException {
        final String rootPath   = "/children";
        final int childrenCzxId    = 1000;
        final int countNodes    = 9;
        final int allNodes    = countNodes+2;

        //  Create the parent node
        dt.createNode(rootPath, new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);

        int parentVersion = dt.getNode(rootPath).stat.getCversion();

        //  Create a children sometimes "before"
        dt.createNode(rootPath + "/test-0", new byte[0], null, 0, parentVersion + 1, childrenCzxId-100, 1);

        //  Create 10 child nodes, all with the same
        for (int i = 1; i <= countNodes; ++i) {
            dt.createNode(rootPath + "/test-" + i, new byte[0], null, 0, parentVersion + 2, childrenCzxId, 1);
        }

        //  Create a children sometimes "after"
        dt.createNode(rootPath + "/test-999", new byte[0], null, 0, parentVersion + 3, childrenCzxId+100, 1);

        //  Asking from a negative would give me all children, and set the watch
        int curWatchCount = dt.getWatchCount();
        List<PathWithStat> result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 1000, -1, 0);
        Assert.assertEquals(allNodes, result.size());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());
        //  Verify that the list is sorted
        String before = "";
        for (final PathWithStat s: result) {
            final String path = s.getPath();
            Assert.assertTrue(String.format("The next path (%s) should be > previons (%s)", path, before),
                    path.compareTo(before) > 0);
            before = path;
        }

        //  Asking with offset minCzxId below childrenCzxId should not skip anything, regardless of offset
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 2, childrenCzxId-1, 3);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("test-1", result.get(0).getPath());
        Assert.assertEquals("test-2", result.get(1).getPath());
        Assert.assertEquals("The watch should not have been set", curWatchCount, dt.getWatchCount());

        //  Asking with offset 5 should skip nodes 1, 2, 3, 4, 5
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 2, childrenCzxId, 5);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("test-6", result.get(0).getPath());
        Assert.assertEquals("test-7", result.get(1).getPath());
        Assert.assertEquals("The watch should not have been set", curWatchCount, dt.getWatchCount());

        //  Asking with offset 5 for more nodes than are there should skip nodes 1, 2, 3, 4, 5 (plus 0 due to zxid)
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 10, childrenCzxId, 5);

        Assert.assertEquals(5, result.size());
        Assert.assertEquals("test-6", result.get(0).getPath());
        Assert.assertEquals("test-7", result.get(1).getPath());
        Assert.assertEquals("test-8", result.get(2).getPath());
        Assert.assertEquals("test-9", result.get(3).getPath());
        Assert.assertEquals("The watch should have been set", curWatchCount+1, dt.getWatchCount());

        //  Asking with offset 5 for fewer nodes than are there should skip nodes 1, 2, 3, 4, 5 (plus 0 due to zxid)
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 4, childrenCzxId, 5);

        Assert.assertEquals(4, result.size());
        Assert.assertEquals("test-6", result.get(0).getPath());
        Assert.assertEquals("test-7", result.get(1).getPath());
        Assert.assertEquals("test-8", result.get(2).getPath());
        Assert.assertEquals("test-9", result.get(3).getPath());
        Assert.assertEquals("The watch should not have been set", curWatchCount, dt.getWatchCount());

        //  Asking from the last created node+1 should return an empty list and set the watch
        curWatchCount = dt.getWatchCount();
        result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 2, 1000 + childrenCzxId, 0);
        Assert.assertTrue("The result should be an empty list", result.isEmpty());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());
    }

    @Test(timeout = 60000)
    public void getChildrenPaginatedEmpty() throws NodeExistsException, NoNodeException {
        final String rootPath   = "/children";

        //  Create the parent node
        dt.createNode(rootPath, new byte[0], null, 0, dt.getNode("/").stat.getCversion()+1, 1, 1);

        //  Asking from a negative would give me all children, and set the watch
        int curWatchCount = dt.getWatchCount();
        List<PathWithStat> result = dt.getPaginatedChildren(rootPath, null, new DummyWatcher(), 100, -1, 0);
        Assert.assertTrue("The result should be empty", result.isEmpty());
        Assert.assertEquals("The watch should have been set", curWatchCount + 1, dt.getWatchCount());
    }

    private class DummyWatcher implements Watcher {
        @Override public void process(WatchedEvent ignored) { }
    }
}
