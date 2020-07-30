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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileTxnLogTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(FileTxnLogTest.class);

    private static final int KB = 1024;

    @Test
    public void testInvalidPreallocSize() {
        assertEquals(10 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, 0),
                "file should not be padded");
        assertEquals(10 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, -1),
                "file should not be padded");
    }

    @Test
    public void testCalculateFileSizeWithPaddingWhenNotToCurrentSize() {
        assertEquals(10 * KB, FilePadding.calculateFileSizeWithPadding(5 * KB, 10 * KB, 10 * KB),
                "file should not be padded");
    }

    @Test
    public void testCalculateFileSizeWithPaddingWhenCloseToCurrentSize() {
        assertEquals(20 * KB, FilePadding.calculateFileSizeWithPadding(7 * KB, 10 * KB, 10 * KB),
                "file should be padded an additional 10 KB");
    }

    @Test
    public void testFileSizeGreaterThanPosition() {
        assertEquals(40 * KB, FilePadding.calculateFileSizeWithPadding(31 * KB, 10 * KB, 10 * KB),
                "file should be padded to 40 KB");
    }

    @Test
    public void testPreAllocSizeSmallerThanTxnData() throws IOException {
        File logDir = ClientBase.createTmpDir();
        FileTxnLog fileTxnLog = new FileTxnLog(logDir);

        // Set a small preAllocSize (.5 MB)
        final int preAllocSize = 500 * KB;
        FilePadding.setPreallocSize(preAllocSize);

        // Create dummy txn larger than preAllocSize
        // Since the file padding inserts a 0, we will fill the data with 0xff to ensure we corrupt the data if we put the 0 in the data
        byte[] data = new byte[2 * preAllocSize];
        Arrays.fill(data, (byte) 0xff);

        // Append and commit 2 transactions to the log
        // Prior to ZOOKEEPER-2249, attempting to pad in association with the second transaction will corrupt the first
        fileTxnLog.append(
            new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.create),
            new CreateTxn("/testPreAllocSizeSmallerThanTxnData1", data, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
        fileTxnLog.commit();
        fileTxnLog.append(
            new TxnHeader(1, 1, 2, 2, ZooDefs.OpCode.create),
            new CreateTxn("/testPreAllocSizeSmallerThanTxnData2", new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
        fileTxnLog.commit();
        fileTxnLog.close();

        // Read the log back from disk, this will throw a java.io.IOException: CRC check failed prior to ZOOKEEPER-2249
        FileTxnLog.FileTxnIterator fileTxnIterator = new FileTxnLog.FileTxnIterator(logDir, 0);

        // Verify the data in the first transaction
        CreateTxn createTxn = (CreateTxn) fileTxnIterator.getTxn();
        assertTrue(Arrays.equals(createTxn.getData(), data));

        // Verify the data in the second transaction
        fileTxnIterator.next();
        createTxn = (CreateTxn) fileTxnIterator.getTxn();
        assertTrue(Arrays.equals(createTxn.getData(), new byte[]{}));
    }

    @Test
    public void testSetPreallocSize() {
        long customPreallocSize = 10101;
        FileTxnLog.setPreallocSize(customPreallocSize);
        assertThat(FilePadding.getPreAllocSize(), is(equalTo(customPreallocSize)));
    }

    public void testSyncThresholdExceedCount() throws IOException {
        // Given ...

        // Set threshold to -1, as after the first commit it takes 0ms to commit to disk.
        java.lang.System.setProperty(FileTxnLog.ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY, "-1");
        ServerStats.Provider providerMock = mock(ServerStats.Provider.class);
        ServerStats serverStats = new ServerStats(providerMock);

        File logDir = ClientBase.createTmpDir();
        FileTxnLog fileTxnLog = new FileTxnLog(logDir);
        fileTxnLog.setServerStats(serverStats);

        // Verify serverStats is 0 before any commit
        assertEquals(0L, serverStats.getFsyncThresholdExceedCount());

        // When ...
        for (int i = 0; i < 50; i++) {
            fileTxnLog.append(
                new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.create),
                new CreateTxn("/testFsyncThresholdCountIncreased", new byte[]{}, ZooDefs.Ids.OPEN_ACL_UNSAFE, false, 0));
            fileTxnLog.commit(); // only 1 commit, otherwise it will be flaky
            // Then ... verify serverStats is updated to the number of commits (as threshold is set to 0)
            assertEquals((long) i + 1, serverStats.getFsyncThresholdExceedCount());
        }
    }

    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;

    // Overhead is about 150 bytes for txn created in this test
    private static final int NODE_SIZE = 1024;
    private final long PREALLOCATE = 512;
    private final long LOG_SIZE_LIMIT = 1024 * 4;

    /**
     * Test that log size get update correctly
     */
    @Test
    public void testGetCurrentLogSize() throws Exception {
        FileTxnLog.setTxnLogSizeLimit(-1);
        File tmpDir = ClientBase.createTmpDir();
        FileTxnLog log = new FileTxnLog(tmpDir);
        FileTxnLog.setPreallocSize(PREALLOCATE);
        CreateRequest record = new CreateRequest(null, new byte[NODE_SIZE], ZooDefs.Ids.OPEN_ACL_UNSAFE, 0);
        int zxid = 1;
        for (int i = 0; i < 4; i++) {
            log.append(new TxnHeader(0, 0, zxid++, 0, 0), record);
            LOG.debug("Current log size: {}", log.getCurrentLogSize());
        }
        log.commit();
        LOG.info("Current log size: {}", log.getCurrentLogSize());
        assertTrue(log.getCurrentLogSize() > (zxid - 1) * NODE_SIZE);
        for (int i = 0; i < 4; i++) {
            log.append(new TxnHeader(0, 0, zxid++, 0, 0), record);
            LOG.debug("Current log size: {}", log.getCurrentLogSize());
        }
        log.commit();
        LOG.info("Current log size: " + log.getCurrentLogSize());
        assertTrue(log.getCurrentLogSize() > (zxid - 1) * NODE_SIZE);
    }

    /**
     * Test that the server can correctly load the data when there are multiple
     * txnlogs per snapshot
     */
    @Test
    public void testLogSizeLimit() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();

        // Need to override preallocate set by setupTestEnv()
        // We don't need to unset these values since each unit test run in
        // a separate JVM instance
        FileTxnLog.setPreallocSize(PREALLOCATE);
        FileTxnLog.setTxnLogSizeLimit(LOG_SIZE_LIMIT);

        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, DummyWatcher.INSTANCE);

        // Generate transactions
        HashSet<Long> zxids = new HashSet<>();
        byte[] bytes = new byte[NODE_SIZE];
        Random random = new Random();
        random.nextBytes(bytes);

        // We will create enough txn to generate 3 logs
        long txnCount = LOG_SIZE_LIMIT / NODE_SIZE / 2 * 5;

        LOG.info("Creating {} txns", txnCount);

        try {
            for (long i = 0; i < txnCount; i++) {
                Stat stat = new Stat();
                zk.create("/node-" + i, bytes, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                zk.getData("/node-" + i, null, stat);
                zxids.add(stat.getCzxid());
            }

        } finally {
            zk.close();
        }

        // shutdown
        f.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");

        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        File[] txnLogs = FileTxnLog.getLogFiles(logDir.listFiles(), 0);

        assertEquals(3, txnLogs.length, "Unexpected number of logs");

        // Log size should not exceed limit by more than one node size;
        long threshold = LOG_SIZE_LIMIT + NODE_SIZE;
        LOG.info(txnLogs[0].getAbsolutePath());
        assertTrue(threshold > txnLogs[0].length(), "Exceed log size limit: " + txnLogs[0].length());
        LOG.info(txnLogs[1].getAbsolutePath());
        assertTrue(threshold > txnLogs[1].length(), "Exceed log size limit " + txnLogs[1].length());

        // Start database only
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.startdata();

        ZKDatabase db = zks.getZKDatabase();

        for (long i = 0; i < txnCount; i++) {
            Stat stat = new Stat();
            byte[] data = db.getData("/node-" + i, stat, null);
            assertArrayEquals(bytes, data, "Missmatch data");
            assertTrue(zxids.contains(stat.getMzxid()), "Unknown zxid ");
        }
    }

}
