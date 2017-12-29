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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StreamCorruptedException;
import java.nio.channels.FileChannel;
import java.util.List;

public class LoadFromLogTest extends ClientBase {
    private static final int NUM_MESSAGES = 300;
    private static final String HOST = "127.0.0.1:";
    protected static final Logger LOG = LoggerFactory.getLogger(LoadFromLogTest.class);

    // setting up the quorum has a transaction overhead for creating and closing the session
    private static final int TRANSACTION_OVERHEAD = 2;
    private static final int TOTAL_TRANSACTIONS = NUM_MESSAGES + TRANSACTION_OVERHEAD;

    @Before
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
            for (int i = 0; i< NUM_MESSAGES; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
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
        LOG.info("Txnlog size: " + storageSize + " bytes");
        Assert.assertTrue("Storage size is greater than zero ",
                (storageSize > 0));
        
        long expectedZxid = 0;
        long lastZxid = 0;
        TxnHeader hdr;
        do {
            hdr = itr.getHeader();
            expectedZxid++;
            Assert.assertTrue("not the same transaction. lastZxid=" + lastZxid + ", zxid=" + hdr.getZxid(), lastZxid != hdr.getZxid());
            Assert.assertTrue("excepting next transaction. expected=" + expectedZxid + ", retreived=" + hdr.getZxid(), (hdr.getZxid() == expectedZxid));
            lastZxid = hdr.getZxid();
        }while(itr.next());

        Assert.assertTrue("processed all transactions. " + expectedZxid + " == " + TOTAL_TRANSACTIONS, (expectedZxid == TOTAL_TRANSACTIONS));
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
            for (int i = 0; i< NUM_MESSAGES; i++) {
                zk.create("/data-", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        stopServer();

        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        File[] logFiles = FileTxnLog.getLogFiles(logDir.listFiles(), 0);
        // Verify that we have at least NUM_MESSAGES / SNAPCOUNT txnlog
        Assert.assertTrue(logFiles.length > NUM_MESSAGES / 100);
        // Delete the first log file, so we will fail to read it back from disk
        Assert.assertTrue("delete the first log file", logFiles[0].delete());

        // Find zxid for the second log
        long secondStartZxid = Util.getZxidFromName(logFiles[1].getName(), "log");

        FileTxnLog txnLog = new FileTxnLog(logDir);
        TxnIterator itr = txnLog.read(1, false);

        // Oldest log is already remove, so this should point to the start of
        // of zxid on the second log
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());

        itr = txnLog.read(secondStartZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());
        Assert.assertTrue(itr.next());

        // Trying to get a second txn on second txnlog give us the
        // the start of second log, since the first one is removed
        long nextZxid = itr.getHeader().getZxid();

        itr = txnLog.read(nextZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());

        // Trying to get a first txn on the third give us the
        // the start of second log, since the first one is removed
        long thirdStartZxid = Util.getZxidFromName(logFiles[2].getName(), "log");
        itr = txnLog.read(thirdStartZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());
        Assert.assertTrue(itr.next());

        nextZxid = itr.getHeader().getZxid();
        itr = txnLog.read(nextZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());
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
            zk.create("/invalidsnap", new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            for (int i = 0; i < NUM_MESSAGES; i++) {
                lastPath = zk.create("/invalidsnap/test-", new byte[0],
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        String[] tokens = lastPath.split("-");
        String expectedPath = "/invalidsnap/test-"
                + String.format("%010d",
                (new Integer(tokens[1])).intValue() + 1);
        ZooKeeperServer zks = getServer(serverFactory);
        long eZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
        // force the zxid to be behind the content
        zks.getZKDatabase().setlastProcessedZxid(
                zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to "
                + zks.getZKDatabase().getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        startServer();
        zks = getServer(serverFactory);
        long fZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();

        // Verify lastProcessedZxid is set correctly
        Assert.assertTrue("Restore failed expected zxid=" + eZxid + " found="
                + fZxid, fZxid == eZxid);
        zk = createZKClient(hostPort);

        // Verify correctness of data and whether sequential znode creation
        // proceeds correctly after this point
        String[] children;
        String path;
        try {
            children = zk.getChildren("/invalidsnap", false).toArray(
                    new String[0]);
            path = zk.create("/invalidsnap/test-", new byte[0],
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        } finally {
            zk.close();
        }
        LOG.info("Expected " + expectedPath + " found " + path);
        Assert.assertTrue("Error in sequential znode creation expected "
                + expectedPath + " found " + path, path.equals(expectedPath));
        Assert.assertTrue("Unexpected number of children " + children.length
                        + " expected " + NUM_MESSAGES,
                (children.length == NUM_MESSAGES));
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
                    zk.create("/invaliddir/test-", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                } catch(NoNodeException e) {
                    //Expected
                }
            }
        } finally {
            zk.close();
        }

        // force the zxid to be behind the content
        ZooKeeperServer zks = getServer(serverFactory);
        zks.getZKDatabase().setlastProcessedZxid(
                zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to "
                + zks.getZKDatabase().getDataTreeLastProcessedZxid());

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
            Assert.fail("Server should not have started without datadir");
        } catch (IOException e) {
            LOG.info("Server failed to start - correct behavior " + e);
        } finally {
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE,
                FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE_DEFAULT);
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
        zk.create("/a", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Stat stat = zk.exists("/a", false);
        long createZxId = stat.getMzxid();
        zk.create("/a/b", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.delete("/a/b", -1);
        zk.delete("/a", -1);
        // force the zxid to be behind the content
        ZooKeeperServer zks = getServer(serverFactory);
        zks.getZKDatabase().setlastProcessedZxid(createZxId);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase()
                .getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        stopServer();

        startServer();
    }

    /**
     * Verify that FileTxnIterator doesn't throw an EOFException when the
     * transaction log header is incomplete.
     */
    @Test
    public void testIncompleteHeader() throws Exception {
        ClientBase.setupTestEnv();
        File dataDir = ClientBase.createTmpDir();
        loadDatabase(dataDir, NUM_MESSAGES);

        File logDir = new File(dataDir, FileTxnSnapLog.version +
                                        FileTxnSnapLog.VERSION);
        FileTxnLog.FileTxnIterator fileItr = new FileTxnLog.FileTxnIterator(logDir, 0);
        List<File> logFiles = fileItr.getStoredFiles();
        int numTransactions = 0;
        while (fileItr.next()) {
            numTransactions++;
        }
        Assert.assertTrue("Verify the number of log files",
                          logFiles.size() > 0);
        Assert.assertTrue("Verify the number of transactions",
                          numTransactions >= NUM_MESSAGES);

        // Truncate the last log file.
        File lastLogFile = logFiles.get(logFiles.size() - 1);
        FileChannel channel = new FileOutputStream(lastLogFile).getChannel();
        channel.truncate(0);
        channel.close();

        // This shouldn't thow Exception.
        fileItr = new FileTxnLog.FileTxnIterator(logDir, 0);
        logFiles = fileItr.getStoredFiles();
        numTransactions = 0;
        while (fileItr.next()) {
        }

        // Verify that the truncated log file does not exist anymore.
        Assert.assertFalse("Verify truncated log file has been deleted",
                           lastLogFile.exists());
    }

    /**
     * Verifies that FileTxnIterator throws CorruptedStreamException if the
     * magic number is corrupted.
     */
    @Test(expected = StreamCorruptedException.class)
    public void testCorruptMagicNumber() throws Exception {
        ClientBase.setupTestEnv();
        File dataDir = ClientBase.createTmpDir();
        loadDatabase(dataDir, NUM_MESSAGES);

        File logDir = new File(dataDir, FileTxnSnapLog.version +
                                        FileTxnSnapLog.VERSION);
        FileTxnLog.FileTxnIterator fileItr = new FileTxnLog.FileTxnIterator(logDir, 0);
        List<File> logFiles = fileItr.getStoredFiles();
        Assert.assertTrue("Verify the number of log files",
                          logFiles.size() > 0);

        // Corrupt the magic number.
        File lastLogFile = logFiles.get(logFiles.size() - 1);
        RandomAccessFile file = new RandomAccessFile(lastLogFile, "rw");
        file.seek(0);
        file.writeByte(123);
        file.close();

        // This should throw CorruptedStreamException.
        while (fileItr.next()) {
        }
    }

    /**
     * Starts a standalone server and create znodes.
     */
    public void loadDatabase(File dataDir, int numEntries) throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        ZooKeeperServer zks = new ZooKeeperServer(dataDir, dataDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(hostPort,CONNECTION_TIMEOUT));
        ZooKeeper zk = ClientBase.createZKClient(hostPort);

        // Generate some transactions that will get logged.
        try {
            for (int i = 0; i < numEntries; i++) {
                zk.create("/load-database-" + i, new byte[0],
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(hostPort, CONNECTION_TIMEOUT));
    }
}
