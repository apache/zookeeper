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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.PathTrie;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataTreeTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(DataTreeTest.class);

    /**
     * For ZOOKEEPER-1755 - Test race condition when taking dumpEphemerals and
     * removing the session related ephemerals from DataTree structure
     */
    @Test
    @Timeout(value = 60)
    public void testDumpEphemerals() throws Exception {
        int count = 1000;
        long session = 1000;
        long zxid = 2000;
        final DataTree dataTree = new DataTree();
        LOG.info("Create {} zkclient sessions and its ephemeral nodes", count);
        createEphemeralNode(session, dataTree, count);
        final AtomicBoolean exceptionDuringDumpEphemerals = new AtomicBoolean(false);
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
            }
        };
        thread.start();
        LOG.debug("Killing {} zkclient sessions and its ephemeral nodes", count);
        killZkClientSession(session, zxid, dataTree, count);
        running.set(false);
        thread.join();
        assertFalse(exceptionDuringDumpEphemerals.get(), "Should have got exception while dumpEphemerals!");
    }

    private void killZkClientSession(long session, long zxid, final DataTree dataTree, int count) {
        for (int i = 0; i < count; i++) {
            dataTree.killSession(session + i, zxid);
        }
    }

    private void createEphemeralNode(long session, final DataTree dataTree, int count) throws NoNodeException, NodeExistsException {
        for (int i = 0; i < count; i++) {
            dataTree.createNode("/test" + i, new byte[0], null, session + i, dataTree.getNode("/").stat.getCversion()
                                                                                     + 1, 1, 1);
        }
    }

    @Test
    @Timeout(value = 60)
    public void testRootWatchTriggered() throws Exception {
        DataTree dt = new DataTree();

        CompletableFuture<Void> fire = new CompletableFuture<>();
        // set a watch on the root node
        dt.getChildren("/", new Stat(), event -> {
            if (event.getPath().equals("/")) {
                fire.complete(null);
            }
        });

        // add a new node, should trigger a watch
        dt.createNode("/xyz", new byte[0], null, 0, dt.getNode("/").stat.getCversion() + 1, 1, 1);

        assertTrue(fire.isDone(), "Root node watch not triggered");
    }

    /**
     * For ZOOKEEPER-1046 test if cversion is getting incremented correctly.
     */
    @Test
    @Timeout(value = 60)
    public void testIncrementCversion() throws Exception {
        try {
            // digestCalculator gets initialized for the new DataTree constructor based on the system property
            ZooKeeperServer.setDigestEnabled(true);
            DataTree dt = new DataTree();
            dt.createNode("/test", new byte[0], null, 0, dt.getNode("/").stat.getCversion() + 1, 1, 1);
            DataNode zk = dt.getNode("/test");
            int prevCversion = zk.stat.getCversion();
            long prevPzxid = zk.stat.getPzxid();
            long digestBefore = dt.getTreeDigest();
            dt.setCversionPzxid("/test/", prevCversion + 1, prevPzxid + 1);
            int newCversion = zk.stat.getCversion();
            long newPzxid = zk.stat.getPzxid();
            assertTrue((newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1),
                "<cversion, pzxid> verification failed. Expected: <" + (prevCversion + 1) + ", "
                    + (prevPzxid + 1) + ">, found: <" + newCversion + ", " + newPzxid + ">");
            assertNotEquals(digestBefore, dt.getTreeDigest());
        } finally {
            ZooKeeperServer.setDigestEnabled(false);
        }
    }

    @Test
    public void testNoCversionRevert() throws Exception {
        DataTree dt = new DataTree();
        DataNode parent = dt.getNode("/");
        dt.createNode("/test", new byte[0], null, 0, parent.stat.getCversion() + 1, 1, 1);
        int currentCversion = parent.stat.getCversion();
        long currentPzxid = parent.stat.getPzxid();
        dt.createNode("/test1", new byte[0], null, 0, currentCversion - 1, 1, 1);
        parent = dt.getNode("/");
        int newCversion = parent.stat.getCversion();
        long newPzxid = parent.stat.getPzxid();
        assertTrue((newCversion >= currentCversion && newPzxid >= currentPzxid),
            "<cversion, pzxid> verification failed. Expected: <"
                                  + currentCversion
                                  + ", "
                                  + currentPzxid
                                  + ">, found: <"
                                  + newCversion
                                  + ", "
                                  + newPzxid
                                  + ">");
    }

    @Test
    public void testPzxidUpdatedWhenDeletingNonExistNode() throws Exception {
        DataTree dt = new DataTree();
        DataNode root = dt.getNode("/");
        long currentPzxid = root.stat.getPzxid();

        // pzxid updated with deleteNode on higher zxid
        long zxid = currentPzxid + 1;
        try {
            dt.deleteNode("/testPzxidUpdatedWhenDeletingNonExistNode", zxid);
        } catch (NoNodeException e) { /* expected */ }
        root = dt.getNode("/");
        currentPzxid = root.stat.getPzxid();
        assertEquals(currentPzxid, zxid);

        // pzxid not updated with smaller zxid
        long prevPzxid = currentPzxid;
        zxid = prevPzxid - 1;
        try {
            dt.deleteNode("/testPzxidUpdatedWhenDeletingNonExistNode", zxid);
        } catch (NoNodeException e) { /* expected */ }
        root = dt.getNode("/");
        currentPzxid = root.stat.getPzxid();
        assertEquals(currentPzxid, prevPzxid);
    }

    @Test
    public void testDigestUpdatedWhenReplayCreateTxnForExistNode() {
        try {
            // digestCalculator gets initialized for the new DataTree constructor based on the system property
            ZooKeeperServer.setDigestEnabled(true);
            DataTree dt = new DataTree();

            dt.processTxn(new TxnHeader(13, 1000, 1, 30, ZooDefs.OpCode.create), new CreateTxn("/foo", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1));

            // create the same node with a higher cversion to simulate the
            // scenario when replaying a create txn for an existing node due
            // to fuzzy snapshot
            dt.processTxn(new TxnHeader(13, 1000, 1, 30, ZooDefs.OpCode.create), new CreateTxn("/foo", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 2));

            // check the current digest value
            assertEquals(dt.getTreeDigest(), dt.getLastProcessedZxidDigest().getDigest());
        } finally {
            ZooKeeperServer.setDigestEnabled(false);
        }
    }

    @Test
    @Timeout(value = 60)
    public void testPathTrieClearOnDeserialize() throws Exception {

        //Create a DataTree with quota nodes so PathTrie get updated
        DataTree dserTree = new DataTree();

        dserTree.createNode("/bug", new byte[20], null, -1, 1, 1, 1);
        dserTree.createNode(Quotas.quotaPath("/bug"), null, null, -1, 1, 1, 1);
        dserTree.createNode(Quotas.limitPath("/bug"), new byte[20], null, -1, 1, 1, 1);
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
        PathTrie pTrie = (PathTrie) pfield.get(dserTree);

        //Check that the node path is removed from pTrie
        assertEquals("/", pTrie.findMaxPrefix("/bug"), "/bug is still in pTrie");
    }


    /* ZOOKEEPER-3531 - org.apache.zookeeper.server.DataTree#serialize calls the aclCache.serialize when doing
     * dataree serialization, however, org.apache.zookeeper.server.ReferenceCountedACLCache#serialize
     * could get stuck at OutputArchieve.writeInt due to potential network/disk issues.
     * This can cause the system experiences hanging issues similar to ZooKeeper-2201.
     * This test verifies the fix that we should not hold ACL cache during dumping aclcache to snapshots
    */
    @Test
    @Timeout(value = 60)
    public void testSerializeDoesntLockACLCacheWhileWriting() throws Exception {
        DataTree tree = new DataTree();
        tree.createNode("/marker", new byte[] { 42 }, null, -1, 1, 1, 1);
        final AtomicBoolean ranTestCase = new AtomicBoolean();
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());
        BinaryOutputArchive oa = new BinaryOutputArchive(out) {
            @Override
            public void writeInt(int size, String tag) throws IOException {
                final Semaphore semaphore = new Semaphore(0);

                new Thread(new Runnable() {
                    @Override
                    public void run() {

                        synchronized (tree.getReferenceCountedAclCache()) {
                            //When we lock ACLCache, allow writeRecord to continue
                            semaphore.release();
                        }
                    }
                }).start();

                try {
                    boolean acquired = semaphore.tryAcquire(30, TimeUnit.SECONDS);
                    //This is the real assertion - could another thread lock
                    //the ACLCache
                    assertTrue(acquired, "Couldn't acquire a lock on the ACLCache while we were calling tree.serialize");
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
                ranTestCase.set(true);

                super.writeInt(size, tag);
            }
        };

        tree.serialize(oa, "test");

        //Let's make sure that we hit the code that ran the real assertion above
        assertTrue(ranTestCase.get(), "Didn't find the expected node");
    }

    /* ZOOKEEPER-3531 - similarly for aclCache.deserialize, we should not hold lock either
    */
    @Test
    @Timeout(value = 60)
    public void testDeserializeDoesntLockACLCacheWhileReading() throws Exception {
        DataTree tree = new DataTree();
        tree.createNode("/marker", new byte[] { 42 }, null, -1, 1, 1, 1);
        final AtomicBoolean ranTestCase = new AtomicBoolean();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        BinaryOutputArchive oa = new BinaryOutputArchive(out);

        tree.serialize(oa, "test");

        DataTree tree2 = new DataTree();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        BinaryInputArchive ia = new BinaryInputArchive(in) {
            @Override
            public long readLong(String tag) throws IOException {
                final Semaphore semaphore = new Semaphore(0);

                new Thread(new Runnable() {
                    @Override
                    public void run() {

                        synchronized (tree2.getReferenceCountedAclCache()) {
                            //When we lock ACLCache, allow readLong to continue
                            semaphore.release();
                        }
                    }
                }).start();

                try {
                    boolean acquired = semaphore.tryAcquire(30, TimeUnit.SECONDS);
                    //This is the real assertion - could another thread lock
                    //the ACLCache
                    assertTrue(acquired, "Couldn't acquire a lock on the ACLCache while we were calling tree.deserialize");
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
                ranTestCase.set(true);

                return super.readLong(tag);
            }
        };

        tree2.deserialize(ia, "test");

        //Let's make sure that we hit the code that ran the real assertion above
        assertTrue(ranTestCase.get(), "Didn't find the expected node");
    }

    /*
     * ZOOKEEPER-2201 - OutputArchive.writeRecord can block for long periods of
     * time, we must call it outside of the node lock.
     * We call tree.serialize, which calls our modified writeRecord method that
     * blocks until it can verify that a separate thread can lock the DataNode
     * currently being written, i.e. that DataTree.serializeNode does not hold
     * the DataNode lock while calling OutputArchive.writeRecord.
     */
    @Test
    @Timeout(value = 60)
    public void testSerializeDoesntLockDataNodeWhileWriting() throws Exception {
        DataTree tree = new DataTree();
        tree.createNode("/marker", new byte[] { 42 }, null, -1, 1, 1, 1);
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
                            assertTrue(acquired, "Couldn't acquire a lock on the DataNode while we were calling tree.serialize");
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
        assertTrue(ranTestCase.get(), "Didn't find the expected node");
    }

    @Test
    @Timeout(value = 60)
    public void testReconfigACLClearOnDeserialize() throws Exception {
        DataTree tree = new DataTree();
        // simulate the upgrading scenario, where the reconfig znode
        // doesn't exist and the acl cache is empty
        tree.deleteNode(ZooDefs.CONFIG_NODE, 1);
        tree.getReferenceCountedAclCache().aclIndex = 0;

        assertEquals(0, tree.aclCacheSize(), "expected to have 1 acl in acl cache map");

        // serialize the data with one znode with acl
        tree.createNode("/bug", new byte[20], ZooDefs.Ids.OPEN_ACL_UNSAFE, -1, 1, 1, 1);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
        tree.serialize(oa, "test");
        baos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        BinaryInputArchive ia = BinaryInputArchive.getArchive(bais);
        tree.deserialize(ia, "test");

        assertEquals(1, tree.aclCacheSize(), "expected to have 1 acl in acl cache map");
        assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, tree.getACL("/bug", new Stat()), "expected to have the same acl");

        // simulate the upgrading case where the config node will be created
        // again after leader election
        tree.addConfigNode();

        assertEquals(2, tree.aclCacheSize(), "expected to have 2 acl in acl cache map");
        assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, tree.getACL("/bug", new Stat()), "expected to have the same acl");
    }

    @Test
    public void testCachedApproximateDataSize() throws Exception {
        DataTree dt = new DataTree();
        long initialSize = dt.approximateDataSize();
        assertEquals(dt.cachedApproximateDataSize(), dt.approximateDataSize());

        // create a node
        dt.createNode("/testApproximateDataSize", new byte[20], null, -1, 1, 1, 1);
        dt.createNode("/testApproximateDataSize1", new byte[20], null, -1, 1, 1, 1);
        assertEquals(dt.cachedApproximateDataSize(), dt.approximateDataSize());

        // update data
        dt.setData("/testApproximateDataSize1", new byte[32], -1, 1, 1);
        assertEquals(dt.cachedApproximateDataSize(), dt.approximateDataSize());

        // delete a node
        dt.deleteNode("/testApproximateDataSize", -1);
        assertEquals(dt.cachedApproximateDataSize(), dt.approximateDataSize());
    }

    @Test
    public void testGetAllChildrenNumber() throws Exception {
        DataTree dt = new DataTree();
        // create a node
        dt.createNode("/all_children_test", new byte[20], null, -1, 1, 1, 1);
        dt.createNode("/all_children_test/nodes", new byte[20], null, -1, 1, 1, 1);
        dt.createNode("/all_children_test/nodes/node1", new byte[20], null, -1, 1, 1, 1);
        dt.createNode("/all_children_test/nodes/node2", new byte[20], null, -1, 1, 1, 1);
        dt.createNode("/all_children_test/nodes/node3", new byte[20], null, -1, 1, 1, 1);
        assertEquals(4, dt.getAllChildrenNumber("/all_children_test"));
        assertEquals(3, dt.getAllChildrenNumber("/all_children_test/nodes"));
        assertEquals(0, dt.getAllChildrenNumber("/all_children_test/nodes/node1"));
        //add these three init nodes:/zookeeper,/zookeeper/quota,/zookeeper/config,so the number is 8.
        assertEquals(8, dt.getAllChildrenNumber("/"));
    }

    @Test
    public void testDeserializeZxidDigest() throws Exception {
        try {
            ZooKeeperServer.setDigestEnabled(true);
            DataTree dt = new DataTree();
            dt.processTxn(new TxnHeader(13, 1000, 1, 30, ZooDefs.OpCode.create),
                    new CreateTxn("/foo", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1), null);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive oa = BinaryOutputArchive.getArchive(baos);
            dt.serializeZxidDigest(oa);
            baos.flush();

            DataTree.ZxidDigest zd = dt.getLastProcessedZxidDigest();
            assertNotNull(zd);

            // deserialize data tree
            InputArchive ia = BinaryInputArchive.getArchive(
                    new ByteArrayInputStream(baos.toByteArray()));
            dt.deserializeZxidDigest(ia, zd.getZxid());
            assertNotNull(dt.getDigestFromLoadedSnapshot());

            ia = BinaryInputArchive.getArchive(new ByteArrayInputStream(baos.toByteArray()));
            dt.deserializeZxidDigest(ia, zd.getZxid() + 1);
            assertNull(dt.getDigestFromLoadedSnapshot());
        } finally {
            ZooKeeperServer.setDigestEnabled(false);
        }
    }

    @Test
    public void testDataTreeMetrics() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        long readBytes1 = 0;
        long readBytes2 = 0;
        long writeBytes1 = 0;
        long writeBytes2 = 0;

        final String TOP1 = "top1";
        final String TOP2 = "ttop2";
        final String TOP1PATH = "/" + TOP1;
        final String TOP2PATH = "/" + TOP2;
        final String CHILD1 = "child1";
        final String CHILD2 = "springishere";
        final String CHILD1PATH = TOP1PATH + "/" + CHILD1;
        final String CHILD2PATH = TOP1PATH + "/" + CHILD2;

        final int TOP2_LEN = 50;
        final int CHILD1_LEN = 100;
        final int CHILD2_LEN = 250;

        DataTree dt = new DataTree();
        dt.createNode(TOP1PATH, null, null, -1, 1, 1, 1);
        writeBytes1 += TOP1PATH.length();
        dt.createNode(TOP2PATH, new byte[TOP2_LEN], null, -1, 1, 1, 1);
        writeBytes2 += TOP2PATH.length() + TOP2_LEN;
        dt.createNode(CHILD1PATH, null, null, -1, 1, 1, 1);
        writeBytes1 += CHILD1PATH.length();
        dt.setData(CHILD1PATH, new byte[CHILD1_LEN], 1, -1, 1);
        writeBytes1 += CHILD1PATH.length() + CHILD1_LEN;
        dt.createNode(CHILD2PATH, new byte[CHILD2_LEN], null, -1, 1, 1, 1);
        writeBytes1 += CHILD2PATH.length() + CHILD2_LEN;
        dt.getData(TOP1PATH, new Stat(), null);
        readBytes1 += TOP1PATH.length() + DataTree.STAT_OVERHEAD_BYTES;
        dt.getData(TOP2PATH, new Stat(), null);
        readBytes2 += TOP2PATH.length() + TOP2_LEN + DataTree.STAT_OVERHEAD_BYTES;
        dt.statNode(CHILD2PATH, null);
        readBytes1 += CHILD2PATH.length() + DataTree.STAT_OVERHEAD_BYTES;
        dt.getChildren(TOP1PATH, new Stat(), null);
        readBytes1 += TOP1PATH.length() + CHILD1.length() + CHILD2.length() + DataTree.STAT_OVERHEAD_BYTES;
        dt.deleteNode(TOP1PATH, 1);
        writeBytes1 += TOP1PATH.length();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        System.out.println("values:" + values);
        assertEquals(writeBytes1, values.get("sum_" + TOP1 + "_write_per_namespace"));
        assertEquals(5L, values.get("cnt_" + TOP1 + "_write_per_namespace"));
        assertEquals(writeBytes2, values.get("sum_" + TOP2 + "_write_per_namespace"));
        assertEquals(1L, values.get("cnt_" + TOP2 + "_write_per_namespace"));

        assertEquals(readBytes1, values.get("sum_" + TOP1 + "_read_per_namespace"));
        assertEquals(3L, values.get("cnt_" + TOP1 + "_read_per_namespace"));
        assertEquals(readBytes2, values.get("sum_" + TOP2 + "_read_per_namespace"));
        assertEquals(1L, values.get("cnt_" + TOP2 + "_read_per_namespace"));
    }

    /**
     * Test digest with general ops in DataTree, check that digest are
     * updated when call different ops.
     */
    @Test
    public void testDigest() throws Exception {
        try {
            // enable diegst check
            ZooKeeperServer.setDigestEnabled(true);

            DataTree dt = new DataTree();

            // create a node and check the digest is updated
            long previousDigest = dt.getTreeDigest();
            dt.createNode("/digesttest", new byte[0], null, -1, 1, 1, 1);
            assertNotEquals(dt.getTreeDigest(), previousDigest);

            // create a child and check the digest is updated
            previousDigest = dt.getTreeDigest();
            dt.createNode("/digesttest/1", "1".getBytes(), null, -1, 2, 2, 2);
            assertNotEquals(dt.getTreeDigest(), previousDigest);

            // check the digest is not chhanged when creating the same node
            previousDigest = dt.getTreeDigest();
            try {
                dt.createNode("/digesttest/1", "1".getBytes(), null, -1, 2, 2, 2);
            } catch (NodeExistsException e) { /* ignore */ }
            assertEquals(dt.getTreeDigest(), previousDigest);

            // check digest with updated data
            previousDigest = dt.getTreeDigest();
            dt.setData("/digesttest/1", "2".getBytes(), 3, 3, 3);
            assertNotEquals(dt.getTreeDigest(), previousDigest);

            // check digest with deleted node
            previousDigest = dt.getTreeDigest();
            dt.deleteNode("/digesttest/1", 5);
            assertNotEquals(dt.getTreeDigest(), previousDigest);
        } finally {
            ZooKeeperServer.setDigestEnabled(false);
        }
    }

}
