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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadFromLogTest extends ClientBase {

    private static final int NUM_MESSAGES = 300;
    protected static final Logger LOG = LoggerFactory.getLogger(LoadFromLogTest.class);

    // setting up the quorum has a transaction overhead for creating and closing the session
    private static final int TRANSACTION_OVERHEAD = 2;
    private static final int TOTAL_TRANSACTIONS = NUM_MESSAGES + TRANSACTION_OVERHEAD;

    @BeforeEach
    public void setUp() throws Exception {
        SyncRequestProcessor.setSnapCount(50);
        super.setUp();
    }

    /**
     * test that all transactions from the Log are loaded, and only once
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testLoad() throws Exception {
        // generate some transactions that will get logged
        ZooKeeper zk = createZKClient(hostPort);
        try {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        stopServer();

        // now verify that the FileTxnLog reads every transaction only once
        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        FileTxnLog txnLog = new FileTxnLog(logDir);
        TxnIterator itr = txnLog.read(0);

        // Check that storage space return some value
        FileTxnIterator fileItr = (FileTxnIterator) itr;
        long storageSize = fileItr.getStorageSize();
        LOG.info("Txnlog size: {} bytes", storageSize);
        assertTrue((storageSize > 0), "Storage size is greater than zero ");

        long expectedZxid = 0;
        long lastZxid = 0;
        TxnHeader hdr;
        do {
            hdr = itr.getHeader();
            expectedZxid++;
            assertTrue(lastZxid != hdr.getZxid(), "not the same transaction. lastZxid=" + lastZxid + ", zxid=" + hdr.getZxid());
            assertTrue((hdr.getZxid() == expectedZxid), "excepting next transaction. expected=" + expectedZxid + ", retrieved=" + hdr.getZxid());
            lastZxid = hdr.getZxid();
        } while (itr.next());

        assertTrue((expectedZxid == TOTAL_TRANSACTIONS), "processed all transactions. " + expectedZxid + " == " + TOTAL_TRANSACTIONS);
    }

    /**
     * test that we fail to load txnlog of a request zxid that is older
     * than what exist on disk
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testLoadFailure() throws Exception {
        // generate some transactions that will get logged
        ZooKeeper zk = createZKClient(hostPort);
        try {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                zk.create("/data-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        stopServer();

        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        File[] logFiles = FileTxnLog.getLogFiles(logDir.listFiles(), 0);
        // Verify that we have at least NUM_MESSAGES / SNAPCOUNT txnlog
        assertTrue(logFiles.length > NUM_MESSAGES / 100);
        // Delete the first log file, so we will fail to read it back from disk
        assertTrue(logFiles[0].delete(), "delete the first log file");

        // Find zxid for the second log
        long secondStartZxid = Util.getZxidFromName(logFiles[1].getName(), "log");

        FileTxnLog txnLog = new FileTxnLog(logDir);
        TxnIterator itr = txnLog.read(1, false);

        // Oldest log is already remove, so this should point to the start of
        // of zxid on the second log
        assertEquals(secondStartZxid, itr.getHeader().getZxid());

        itr = txnLog.read(secondStartZxid, false);
        assertEquals(secondStartZxid, itr.getHeader().getZxid());
        assertTrue(itr.next());

        // Trying to get a second txn on second txnlog give us the
        // the start of second log, since the first one is removed
        long nextZxid = itr.getHeader().getZxid();

        itr = txnLog.read(nextZxid, false);
        assertEquals(secondStartZxid, itr.getHeader().getZxid());

        // Trying to get a first txn on the third give us the
        // the start of second log, since the first one is removed
        long thirdStartZxid = Util.getZxidFromName(logFiles[2].getName(), "log");
        itr = txnLog.read(thirdStartZxid, false);
        assertEquals(secondStartZxid, itr.getHeader().getZxid());
        assertTrue(itr.next());

        nextZxid = itr.getHeader().getZxid();
        itr = txnLog.read(nextZxid, false);
        assertEquals(secondStartZxid, itr.getHeader().getZxid());
    }

    /**
     * Test we can restore the snapshot that has data ahead of the zxid
     * of the snapshot file.
     */
    @Test
    public void testRestore() throws Exception {
        // generate some transactions
        ZooKeeper zk = createZKClient(hostPort);
        String lastPath = null;
        try {
            zk.create("/invalidsnap", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            for (int i = 0; i < NUM_MESSAGES; i++) {
                lastPath = zk.create("/invalidsnap/test-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        String[] tokens = lastPath.split("-");
        String expectedPath = "/invalidsnap/test-" + String.format("%010d", (Integer.parseInt(tokens[1])) + 1);
        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        long eZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
        // force the zxid to be behind the content
        zks.getZKDatabase().setlastProcessedZxid(zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase().getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        startServer();
        zks = serverFactory.getZooKeeperServer();
        long fZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();

        // Verify lastProcessedZxid is set correctly
        assertTrue(fZxid == eZxid, "Restore failed expected zxid=" + eZxid + " found=" + fZxid);
        zk = createZKClient(hostPort);

        // Verify correctness of data and whether sequential znode creation
        // proceeds correctly after this point
        String[] children;
        String path;
        try {
            children = zk.getChildren("/invalidsnap", false).toArray(new String[0]);
            path = zk.create("/invalidsnap/test-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        } finally {
            zk.close();
        }
        LOG.info("Expected {} found {}", expectedPath, path);
        assertTrue(path.equals(expectedPath),
                "Error in sequential znode creation expected " + expectedPath + " found " + path);
        assertTrue((children.length == NUM_MESSAGES),
                "Unexpected number of children " + children.length + " expected " + NUM_MESSAGES);
    }

    /**
     * Test we can restore a snapshot that has errors and data ahead of the zxid
     * of the snapshot file.
     */
    @Test
    public void testRestoreWithTransactionErrors() throws Exception {
        // generate some transactions
        ZooKeeper zk = createZKClient(hostPort);
        try {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                try {
                    zk.create("/invaliddir/test-", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                } catch (NoNodeException e) {
                    //Expected
                }
            }
        } finally {
            zk.close();
        }

        // force the zxid to be behind the content
        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        zks.getZKDatabase().setlastProcessedZxid(zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase().getDataTreeLastProcessedZxid());

        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        startServer();
    }

    /**
     * Verify snap/log dir create with/without autocreate enabled.
     */
    @Test
    public void testDatadirAutocreate() throws Exception {
        stopServer();

        try {
            // now verify autocreate off works
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");
            tmpDir = createTmpDir();
            startServer();
            fail("Server should not have started without datadir");
        } catch (IOException e) {
            LOG.debug("Server failed to start - correct behavior", e);
        } finally {
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE_DEFAULT);
        }
    }

    /**
     * ZOOKEEPER-1573: test restoring a snapshot with deleted txns ahead of the
     * snapshot file's zxid.
     */
    @Test
    public void testReloadSnapshotWithMissingParent() throws Exception {
        // create transactions to create the snapshot with create/delete pattern
        ZooKeeper zk = createZKClient(hostPort);
        zk.create("/a", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Stat stat = zk.exists("/a", false);
        long createZxId = stat.getMzxid();
        zk.create("/a/b", "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/a/b", -1);
        zk.delete("/a", -1);
        // force the zxid to be behind the content
        ZooKeeperServer zks = serverFactory.getZooKeeperServer();
        zks.getZKDatabase().setlastProcessedZxid(createZxId);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase().getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        startServer();
    }

}
