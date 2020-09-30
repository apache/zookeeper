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

package org.apache.zookeeper.server.persistence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.util.AssertEqual;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocksDBIncrementalSnapshotTest {
    private static final Logger LOG = LoggerFactory.getLogger(RocksDBIncrementalSnapshotTest.class);

    private File tmpDir;
    private FileTxnSnapLog snapLog;
    private ZKDatabase zkDb;
    private long zxid;
    private final long DB_WRITE_BUFFER_SIZE = 1024 * 1024 * 1024;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty(RocksDBSnap.ROCKSDB_WRITE_BUFFER_SIZE, Long.toString(DB_WRITE_BUFFER_SIZE));
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, RocksDBSnap.class.getName());
        ZooKeeperServer.setDigestEnabled(true);
        tmpDir = ClientBase.createEmptyTestDir();
        snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        zkDb = new ZKDatabase(snapLog);
    }

    @AfterEach
    public void tearDown() throws Exception {
        zkDb.close();
        tmpDir = null;
    }

    @Test
    public void testCreateTxnSuccess() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 0);
        TxnHeader txnHeader = new TxnHeader(1, 1, zxid, 2, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/" + 1, "data".getBytes(StandardCharsets.UTF_8),
            null, false, 1);
        zkDb.processTxn(txnHeader, txn, null);

        zxid = ZxidUtils.makeZxid(1, 1);
        txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.create2);
        txn = new CreateTxn("/foo", "fooData".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, txn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        Assert.assertEquals("data", new String(dserTree.getNode("/" + 1).getData(),
            StandardCharsets.UTF_8));
        Assert.assertEquals("fooData", new String(dserTree.getNode("/foo").getData(),
            StandardCharsets.UTF_8));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testCreateTxnNodeExists() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 1);
        TxnHeader txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/foo", "fooData".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, txn, null);

        zxid = ZxidUtils.makeZxid(1, 2);
        txnHeader = new TxnHeader(1, 3, zxid, 4, ZooDefs.OpCode.create);
        txn = new CreateTxn("/foo", "bug".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, txn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);
        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        Assert.assertEquals("fooData", new String(dserTree.getNode("/foo").getData(),
            StandardCharsets.UTF_8));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testCreateTxnNoNode() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 1);
        TxnHeader txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/foo/bug", "fooData".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, txn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testSetACLTxnSuccess() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 1);
        TxnHeader txnHeader = new TxnHeader(1, 1, zxid, 2, ZooDefs.OpCode.create);
        CreateTxn createTxn = new CreateTxn("/foo", "data".getBytes(StandardCharsets.UTF_8),
            null, false, 1);
        zkDb.processTxn(txnHeader, createTxn, null);

        zxid = ZxidUtils.makeZxid(1, 2);
        txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.setACL);
        SetACLTxn setACLTxn = new SetACLTxn("/foo", ZooDefs.Ids.OPEN_ACL_UNSAFE, -1);
        zkDb.processTxn(txnHeader, setACLTxn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testDeleteTxnSuccess() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 1);
        TxnHeader txnHeader = new TxnHeader(1, 1, zxid, 2, ZooDefs.OpCode.create);
        CreateTxn createTxn = new CreateTxn("/foo", "data".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, createTxn, null);

        zxid = ZxidUtils.makeZxid(1, 2);
        txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.delete);
        DeleteTxn deleteTxn = new DeleteTxn("/foo");
        zkDb.processTxn(txnHeader, deleteTxn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        Assert.assertEquals(null, dserTree.getNode("/foo"));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testSetDataSuccess() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 2);
        TxnHeader txnHeader = new TxnHeader(1, 1, zxid, 2, ZooDefs.OpCode.create);
        CreateTxn createTxn = new CreateTxn("/foo", "data".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        zkDb.processTxn(txnHeader, createTxn, null);

        zxid = ZxidUtils.makeZxid(1, 3);
        txnHeader = new TxnHeader(1, 2, zxid, 3, ZooDefs.OpCode.setData);
        SetDataTxn setDataTxn = new SetDataTxn("/foo",
            "newData".getBytes(StandardCharsets.UTF_8), 2);
        zkDb.processTxn(txnHeader, setDataTxn, null);
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        Assert.assertEquals("newData", new String(dserTree.getNode("/foo").getData(),
            StandardCharsets.UTF_8));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testMultiTxnSuccess() throws Exception {
        zkDb.save(true);

        zxid = ZxidUtils.makeZxid(1, 3);
        TxnHeader txnHeader = new TxnHeader(1, 1, zxid, 2, ZooDefs.OpCode.multi);
        List<Txn> txnList = new ArrayList<Txn>();

        // Create node.
        for (int i = 0; i < 5; i++) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            CreateTxn createTxn = new CreateTxn("/znode-" + i, ("data-" + i).getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
            createTxn.serialize(boa, "request");
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            Txn txact = new Txn(ZooDefs.OpCode.create, bb.array());
            txnList.add(txact);
        }
        MultiTxn multiTxn = new MultiTxn(txnList);
        zkDb.processTxn(txnHeader, multiTxn, null);

        zxid = ZxidUtils.makeZxid(1, 4);
        txnHeader = new TxnHeader(2, 2, zxid, 3, ZooDefs.OpCode.multi);
        // Set data.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
        SetDataTxn setDataTxn = new SetDataTxn("/znode-2", "newData".getBytes(), 2);
        setDataTxn.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
        Txn txact = new Txn(ZooDefs.OpCode.setData, bb.array());
        txnList.add(txact);

        // Delete node.
        baos = new ByteArrayOutputStream();
        boa = BinaryOutputArchive.getArchive(baos);
        DeleteTxn deleteTxn = new DeleteTxn("/znode-3");
        deleteTxn.serialize(boa, "request");
        bb = ByteBuffer.wrap(baos.toByteArray());
        txact = new Txn(ZooDefs.OpCode.delete, bb.array());
        txnList.add(txact);

        multiTxn = new MultiTxn(txnList);
        zkDb.processTxn(txnHeader, multiTxn, null);

        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.restore(dserTree, sessions, (hdr, rec, digest) -> {});

        Assert.assertEquals("newData",
            new String(dserTree.getNode("/znode-2").getData(), StandardCharsets.UTF_8));
        Assert.assertEquals(null, dserTree.getNode("/znode-3"));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testRocksDBApplyTxnWhenLoadingDatabase() throws Exception {
        zkDb.save(true);

        TxnHeader txnHeader = new TxnHeader(1, 1, 1, 2, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/1", "data1".getBytes(StandardCharsets.UTF_8), null,
            false, 1);
        Request request = new Request(1, 1, 1, txnHeader, txn, 1);
        zkDb.append(request);
        zkDb.commit();

        txnHeader = new TxnHeader(2, 2, 2, 3, ZooDefs.OpCode.create2);
        txn = new CreateTxn("/2", "data2".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        request = new Request(1, 1, 1, txnHeader, txn, 1);
        zkDb.append(request);
        zkDb.commit();

        txnHeader = new TxnHeader(3, 3, 3, 4, ZooDefs.OpCode.setData);
        SetDataTxn setDataTxn = new SetDataTxn("/1",
            "newData".getBytes(StandardCharsets.UTF_8), 2);
        request = new Request(1, 1, 1, txnHeader, setDataTxn, 1);
        zkDb.append(request);
        zkDb.commit();

        // When loading the database, in-memory data tree will be updated when the server processing
        // transactions from the txnLog. The snapshot should also be updated because the server will
        // apply all these transactions to the snapshot.
        zkDb.loadDataBase();
        // this will flush instead of taking a full snapshot.
        zkDb.save(false);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.getSnapshot().deserialize(dserTree, sessions);

        Assert.assertEquals("newData", new String(dserTree.getNode("/1").getData(),
            StandardCharsets.UTF_8));
        Assert.assertEquals("data2", new String(dserTree.getNode("/2").getData(),
            StandardCharsets.UTF_8));
        AssertEqual.assertDBEqual(zkDb.getDataTree(), dserTree);
    }

    @Test
    public void testFileToRocksDBSnapApplyTxnWhenLoadingDatabase() throws Exception {
        zkDb.close();
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, FileSnap.class.getName());
        snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        zkDb = new ZKDatabase(snapLog);
        // Take a snapshot in files.
        zkDb.save(true);
        zkDb.close();

        // Start FileToRocksDBSnap.
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, FileToRocksDBSnap.class.getName());
        snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        zkDb = new ZKDatabase(snapLog);

        TxnHeader txnHeader = new TxnHeader(1, 1, 1, 2, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/1", "data1".getBytes(StandardCharsets.UTF_8),
            null, false, 1);
        Request request = new Request(1, 1, 1, txnHeader, txn, 1);
        zkDb.append(request);
        zkDb.commit();

        txnHeader = new TxnHeader(2, 2, 2, 3, ZooDefs.OpCode.create2);
        txn = new CreateTxn("/2", "data2".getBytes(StandardCharsets.UTF_8),
            ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 1);
        request = new Request(1, 1, 1, txnHeader, txn, 1);
        zkDb.append(request);
        zkDb.commit();

        txnHeader = new TxnHeader(3, 3, 3, 4, ZooDefs.OpCode.delete);
        DeleteTxn deleteTxn = new DeleteTxn("/1");
        request = new Request(1, 1, 1, txnHeader, deleteTxn, 1);
        zkDb.append(request);
        zkDb.commit();

        // FileToRocksDBSnap will take a snapshot in RocksDB when loading the database then
        // apply transactions to it.
        zkDb.loadDataBase();
        // This will flush instead of taking a full snapshot.
        zkDb.save(false);

        DataTree dataTree = zkDb.getDataTree();

        // Use RocksDBSnap to deserialize the data tree from the snapshot.
        zkDb.close();
        System.setProperty(SnapshotFactory.ZOOKEEPER_SNAPSHOT_NAME, RocksDBSnap.class.getName());
        snapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        zkDb = new ZKDatabase(snapLog);

        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        DataTree dserTree = new DataTree();
        snapLog.getSnapshot().deserialize(dserTree, sessions);

        Assert.assertEquals(null, dserTree.getNode("/1"));
        Assert.assertEquals("data2", new String(dserTree.getNode("/2").getData(),
            StandardCharsets.UTF_8));
        AssertEqual.assertDBEqual(dataTree, dserTree);
    }
}

