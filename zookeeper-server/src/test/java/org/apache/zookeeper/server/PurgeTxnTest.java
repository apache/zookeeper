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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CheckedOutputStream;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileSnap;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PurgeTxnTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeTxnTest.class);
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    private static final long OP_TIMEOUT_IN_MILLIS = 120000;
    private File tmpDir;

    @BeforeEach
    public void setUp() throws Exception {
        tmpDir = ClientBase.createTmpDir();
    }

    @AfterEach
    public void teardown() {
        if (null != tmpDir) {
            ClientBase.recursiveDelete(tmpDir);
        }
    }

    /**
     * test the purge
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testPurge() throws Exception {
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {
            for (int i = 0; i < 2000; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.getTxnLogFactory().close();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");
        // now corrupt the snapshot
        PurgeTxnLog.purge(tmpDir, tmpDir, 3);
        FileTxnSnapLog snaplog = new FileTxnSnapLog(tmpDir, tmpDir);
        List<File> listLogs = snaplog.findNValidSnapshots(4);
        int numSnaps = 0;
        for (File ff : listLogs) {
            if (ff.getName().startsWith("snapshot")) {
                numSnaps++;
            }
        }
        assertTrue((numSnaps == 3), "exactly 3 snapshots ");
        snaplog.close();
        zks.shutdown();
    }

    /**
     * Tests purge when logs are rolling or a new snapshot is created, then
     * these newer files should alse be excluded in the current cycle.
     *
     * For frequent snapshotting, configured SnapCount to 30. There are three
     * threads which will create 1000 znodes each and simultaneously do purge
     * call
     */
    @Test
    public void testPurgeWhenLogRollingInProgress() throws Exception {
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(30);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        final ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        final CountDownLatch doPurge = new CountDownLatch(1);
        final CountDownLatch purgeFinished = new CountDownLatch(1);
        final AtomicBoolean opFailed = new AtomicBoolean(false);
        new Thread() {
            public void run() {
                try {
                    doPurge.await(OP_TIMEOUT_IN_MILLIS / 2, TimeUnit.MILLISECONDS);
                    PurgeTxnLog.purge(tmpDir, tmpDir, 3);
                } catch (IOException ioe) {
                    LOG.error("Exception when purge", ioe);
                    opFailed.set(true);
                } catch (InterruptedException ie) {
                    LOG.error("Exception when purge", ie);
                    opFailed.set(true);
                } finally {
                    purgeFinished.countDown();
                }
            }
        }.start();
        final int thCount = 3;
        List<String> znodes = manyClientOps(zk, doPurge, thCount, "/invalidsnap");
        assertTrue(purgeFinished.await(OP_TIMEOUT_IN_MILLIS, TimeUnit.MILLISECONDS), "Purging is not finished!");
        assertFalse(opFailed.get(), "Purging failed!");
        for (String znode : znodes) {
            try {
                zk.getData(znode, false, null);
            } catch (Exception ke) {
                LOG.error("Unexpected exception when visiting znode!", ke);
                fail("Unexpected exception when visiting znode!");
            }
        }
        zk.close();
        f.shutdown();
        zks.shutdown();
        zks.getTxnLogFactory().close();
    }

    /**
     * Tests finding n recent valid snapshots from set of snapshots and data logs
     */
    @Test
    public void testFindNValidSnapshots() throws Exception {
        int nRecentSnap = 4; // n recent snap shots
        int nRecentCount = 30;
        int offset = 0;

        File version2 = new File(tmpDir.toString(), "version-2");
        assertTrue(version2.mkdir(), "Failed to create version_2 dir:" + version2.toString());

        // Test that with no snaps, findNValidSnapshots returns empty list
        FileTxnSnapLog txnLog = new FileTxnSnapLog(tmpDir, tmpDir);
        List<File> foundSnaps = txnLog.findNValidSnapshots(1);
        assertEquals(0, foundSnaps.size());

        List<File> expectedNRecentSnapFiles = new ArrayList<File>();
        int counter = offset + (2 * nRecentCount);
        for (int i = 0; i < nRecentCount; i++) {
            // simulate log file
            File logFile = new File(version2 + "/log." + Long.toHexString(--counter));
            assertTrue(logFile.createNewFile(), "Failed to create log File:" + logFile.toString());
            // simulate snapshot file
            File snapFile = new File(version2 + "/snapshot." + Long.toHexString(--counter));
            assertTrue(snapFile.createNewFile(), "Failed to create snap File:" + snapFile.toString());
            makeValidSnapshot(snapFile);
            // add the n recent snap files for assertion
            if (i < nRecentSnap) {
                expectedNRecentSnapFiles.add(snapFile);
            }
        }

        // Test that when we ask for recent snaps we get the number we asked for and
        // the files we expected
        List<File> nRecentValidSnapFiles = txnLog.findNValidSnapshots(nRecentSnap);
        assertEquals(4, nRecentValidSnapFiles.size(), "exactly 4 snapshots ");
        expectedNRecentSnapFiles.removeAll(nRecentValidSnapFiles);
        assertEquals(0, expectedNRecentSnapFiles.size(), "Didn't get the recent snap files");

        // Test that when asking for more snaps than we created, we still only get snaps
        // not logs or anything else (per ZOOKEEPER-2420)
        nRecentValidSnapFiles = txnLog.findNValidSnapshots(nRecentCount + 5);
        assertEquals(nRecentCount, nRecentValidSnapFiles.size());
        for (File f : nRecentValidSnapFiles) {
            assertTrue((Util.getZxidFromName(f.getName(), "snapshot") != -1),
                    "findNValidSnapshots() returned a non-snapshot: " + f.getPath());
        }

        txnLog.close();
    }

    /**
     * Tests purge where the data directory contains old snapshots and data
     * logs, newest snapshots and data logs, (newest + n) snapshots and data
     * logs
     */
    @Test
    public void testSnapFilesGreaterThanToRetain() throws Exception {
        int nRecentCount = 4;
        int fileAboveRecentCount = 4;
        int fileToPurgeCount = 2;
        AtomicInteger offset = new AtomicInteger(0);
        File version2 = new File(tmpDir.toString(), "version-2");
        assertTrue(version2.mkdir(), "Failed to create version_2 dir:" + version2.toString());
        List<File> snapsToPurge = new ArrayList<File>();
        List<File> logsToPurge = new ArrayList<File>();
        List<File> snaps = new ArrayList<File>();
        List<File> logs = new ArrayList<File>();
        List<File> snapsAboveRecentFiles = new ArrayList<File>();
        List<File> logsAboveRecentFiles = new ArrayList<File>();
        createDataDirFiles(offset, fileToPurgeCount, false, version2, snapsToPurge, logsToPurge);
        createDataDirFiles(offset, nRecentCount, false, version2, snaps, logs);
        logs.add(logsToPurge.remove(0)); // log that precedes first retained snapshot is also retained
        createDataDirFiles(offset, fileAboveRecentCount, false, version2, snapsAboveRecentFiles, logsAboveRecentFiles);

        /**
         * The newest log file preceding the oldest retained snapshot is not removed as it may
         * contain transactions newer than the oldest snapshot.
         */
        logsToPurge.remove(0);

        FileTxnSnapLog txnLog = new FileTxnSnapLog(tmpDir, tmpDir);
        PurgeTxnLog.purgeOlderSnapshots(txnLog, snaps.get(snaps.size() - 1));
        txnLog.close();
        verifyFilesAfterPurge(snapsToPurge, false);
        verifyFilesAfterPurge(logsToPurge, false);
        verifyFilesAfterPurge(snaps, true);
        verifyFilesAfterPurge(logs, true);
        verifyFilesAfterPurge(snapsAboveRecentFiles, true);
        verifyFilesAfterPurge(logsAboveRecentFiles, true);
    }

    /**
     * Tests purge where the data directory contains snap files and log files equals to the
     * number of files to be retained
     */
    @Test
    public void testSnapFilesEqualsToRetain() throws Exception {
        internalTestSnapFilesEqualsToRetain(false);
    }

    /**
     * Tests purge where the data directory contains snap files equals to the
     * number of files to be retained, and a log file that precedes the earliest snapshot
     */
    @Test
    public void testSnapFilesEqualsToRetainWithPrecedingLog() throws Exception {
        internalTestSnapFilesEqualsToRetain(true);
    }

    public void internalTestSnapFilesEqualsToRetain(boolean testWithPrecedingLogFile) throws Exception {
        int nRecentCount = 3;
        AtomicInteger offset = new AtomicInteger(0);
        File version2 = new File(tmpDir.toString(), "version-2");
        assertTrue(version2.mkdir(), "Failed to create version_2 dir:" + version2.toString());
        List<File> snaps = new ArrayList<File>();
        List<File> logs = new ArrayList<File>();
        createDataDirFiles(offset, nRecentCount, testWithPrecedingLogFile, version2, snaps, logs);

        FileTxnSnapLog txnLog = new FileTxnSnapLog(tmpDir, tmpDir);
        PurgeTxnLog.purgeOlderSnapshots(txnLog, snaps.get(snaps.size() - 1));
        txnLog.close();
        verifyFilesAfterPurge(snaps, true);
        verifyFilesAfterPurge(logs, true);
    }

    /**
     * Tests purge where the data directory contains old snapshots and data
     * logs, newest snapshots and data logs
     */
    @Test
    public void testSnapFilesLessThanToRetain() throws Exception {
        int nRecentCount = 4;
        int fileToPurgeCount = 2;
        AtomicInteger offset = new AtomicInteger(0);
        File version2 = new File(tmpDir.toString(), "version-2");
        assertTrue(version2.mkdir(), "Failed to create version_2 dir:" + version2.toString());
        List<File> snapsToPurge = new ArrayList<File>();
        List<File> logsToPurge = new ArrayList<File>();
        List<File> snaps = new ArrayList<File>();
        List<File> logs = new ArrayList<File>();
        createDataDirFiles(offset, fileToPurgeCount, false, version2, snapsToPurge, logsToPurge);
        createDataDirFiles(offset, nRecentCount, false, version2, snaps, logs);
        logs.add(logsToPurge.remove(0)); // log that precedes first retained snapshot is also retained

        /**
         * The newest log file preceding the oldest retained snapshot is not removed as it may
         * contain transactions newer than the oldest snapshot.
         */
        logsToPurge.remove(0);

        FileTxnSnapLog txnLog = new FileTxnSnapLog(tmpDir, tmpDir);
        PurgeTxnLog.purgeOlderSnapshots(txnLog, snaps.get(snaps.size() - 1));
        txnLog.close();
        verifyFilesAfterPurge(snapsToPurge, false);
        verifyFilesAfterPurge(logsToPurge, false);
        verifyFilesAfterPurge(snaps, true);
        verifyFilesAfterPurge(logs, true);
    }

    /**
     * PurgeTxnLog is called with dataLogDir snapDir -n count This test case
     * verify these values are parsed properly and functionality works fine
     */
    @Test
    public void testPurgeTxnLogWithDataDir() throws Exception {
        File dataDir = new File(tmpDir, "dataDir");
        File dataLogDir = new File(tmpDir, "dataLogDir");

        File dataDirVersion2 = new File(dataDir, "version-2");
        dataDirVersion2.mkdirs();
        File dataLogDirVersion2 = new File(dataLogDir, "version-2");
        dataLogDirVersion2.mkdirs();

        // create dummy log and transaction file
        int totalFiles = 20;

        // create transaction and snapshot files in different-different
        // directories
        for (int i = 0; i < totalFiles; i++) {
            // simulate log file
            File logFile = new File(dataLogDirVersion2, "log." + Long.toHexString(i));
            logFile.createNewFile();
            // simulate snapshot file
            File snapFile = new File(dataDirVersion2, "snapshot." + Long.toHexString(i));
            snapFile.createNewFile();
            makeValidSnapshot(snapFile);
        }

        int numberOfSnapFilesToKeep = 10;
        // scenario where four parameter are passed
        String[] args = new String[]{dataLogDir.getAbsolutePath(), dataDir.getAbsolutePath(), "-n", Integer.toString(numberOfSnapFilesToKeep)};
        PurgeTxnLog.main(args);

        assertEquals(numberOfSnapFilesToKeep, dataDirVersion2.listFiles().length);
        // Since for each snapshot we have a log file with same zxid, expect same # logs as snaps to be kept
        assertEquals(numberOfSnapFilesToKeep, dataLogDirVersion2.listFiles().length);
    }

    /**
     * PurgeTxnLog is called with dataLogDir -n count This test case verify
     * these values are parsed properly and functionality works fine
     */
    @Test
    public void testPurgeTxnLogWithoutDataDir() throws Exception {
        File dataDir = new File(tmpDir, "dataDir");
        File dataLogDir = new File(tmpDir, "dataLogDir");

        File dataDirVersion2 = new File(dataDir, "version-2");
        dataDirVersion2.mkdirs();
        File dataLogDirVersion2 = new File(dataLogDir, "version-2");
        dataLogDirVersion2.mkdirs();

        // create dummy log and transaction file
        int totalFiles = 20;

        // create transaction and snapshot files in data directory
        for (int i = 0; i < totalFiles; i++) {
            // simulate log file
            File logFile = new File(dataLogDirVersion2, "log." + Long.toHexString(i));
            logFile.createNewFile();
            // simulate snapshot file
            File snapFile = new File(dataLogDirVersion2, "snapshot." + Long.toHexString(i));
            snapFile.createNewFile();
            makeValidSnapshot(snapFile);
        }

        int numberOfSnapFilesToKeep = 10;
        // scenario where only three parameter are passed
        String[] args = new String[]{dataLogDir.getAbsolutePath(), "-n", Integer.toString(numberOfSnapFilesToKeep)};
        PurgeTxnLog.main(args);
        assertEquals(
                numberOfSnapFilesToKeep
                        * 2, // Since for each snapshot we have a log file with same zxid, expect same # logs as snaps to be kept
                dataLogDirVersion2.listFiles().length);
    }

    /**
     * Verifies that purge does not delete any log files which started before the oldest retained
     * snapshot but which might extend beyond it.
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testPurgeDoesNotDeleteOverlappingLogFile() throws Exception {
        // Setting used for snapRetainCount in this test.
        final int SNAP_RETAIN_COUNT = 3;
        // Number of znodes this test creates in each snapshot.
        final int NUM_ZNODES_PER_SNAPSHOT = 100;
        /**
         * Set a sufficiently high snapCount to ensure that we don't rollover the log.  Normally,
         * the default value (100K at time of this writing) would ensure this, but we make that
         * dependence explicit here to make the test future-proof.  Not rolling over the log is
         * important for this test since we are testing retention of the one and only log file which
         * predates each retained snapshot.
         */
        SyncRequestProcessor.setSnapCount(SNAP_RETAIN_COUNT * NUM_ZNODES_PER_SNAPSHOT * 10);

        // Create Zookeeper and connect to it.
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);

        // Unique identifier for each znode that we create.
        int unique = 0;
        try {
            /**
             * Create some znodes and take a snapshot.  Repeat this until we have SNAP_RETAIN_COUNT
             * snapshots.  Do not rollover the log.
             */
            for (int snapshotCount = 0; snapshotCount < SNAP_RETAIN_COUNT; snapshotCount++) {
                for (int i = 0; i < 100; i++, unique++) {
                    zk.create("/snap-" + unique, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
                zks.takeSnapshot();
            }
            // Create some additional znodes without taking a snapshot afterwards.
            for (int i = 0; i < 100; i++, unique++) {
                zk.create("/snap-" + unique, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }

        // Shutdown Zookeeper.
        f.shutdown();
        zks.getTxnLogFactory().close();
        zks.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");

        // Purge snapshot and log files.
        PurgeTxnLog.purge(tmpDir, tmpDir, SNAP_RETAIN_COUNT);

        // Initialize Zookeeper again from the same dataDir.
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        zk = ClientBase.createZKClient(HOSTPORT);

        /**
         * Verify that the last znode that was created above exists.  This znode's creation was
         * captured by the transaction log which was created before any of the above
         * SNAP_RETAIN_COUNT snapshots were created, but it's not captured in any of these
         * snapshots.  So for it it exist, the (only) existing log file should not have been purged.
         */
        final String lastZnode = "/snap-" + (unique - 1);
        final Stat stat = zk.exists(lastZnode, false);
        assertNotNull(stat, "Last znode does not exist: " + lastZnode);

        // Shutdown for the last time.
        f.shutdown();
        zks.getTxnLogFactory().close();
        zks.shutdown();
    }

    @Test
    public void testPurgeTxnLogWhenRecentSnapshotsAreAllInvalid() throws Exception {
        File dataDir = new File(tmpDir, "dataDir");
        File dataLogDir = new File(tmpDir, "dataLogDir");

        File dataDirVersion2 = new File(dataDir, "version-2");
        dataDirVersion2.mkdirs();
        File dataLogDirVersion2 = new File(dataLogDir, "version-2");
        dataLogDirVersion2.mkdirs();

        // create dummy log and transaction file
        int totalFiles = 10;
        int numberOfSnapFilesToKeep = 3;

        // create transaction and snapshot files in different-different
        // directories
        for (int i = 0; i < totalFiles; i++) {
            // simulate log file
            File logFile = new File(dataLogDirVersion2, "log." + Long.toHexString(i));
            logFile.createNewFile();
            // simulate snapshot file
            File snapFile = new File(dataDirVersion2, "snapshot." + Long.toHexString(i));
            snapFile.createNewFile();
            if (i < (totalFiles - numberOfSnapFilesToKeep)) {
                makeValidSnapshot(snapFile);
            } else {
                makeInvalidSnapshot(snapFile);
            }
        }

        // scenario where four parameter are passed
        String[] args = new String[]{dataLogDir.getAbsolutePath(), dataDir.getAbsolutePath(), "-n", Integer.toString(numberOfSnapFilesToKeep)};
        PurgeTxnLog.main(args);
        //Since the recent 3 snapshots are all invalid,when purging, we can assert that 6 snapshot files are retained(3 invalid snapshots and 3 retained valid snapshots)
        assertEquals(numberOfSnapFilesToKeep + numberOfSnapFilesToKeep, dataDirVersion2.listFiles().length);
        // Since for each snapshot we have a log file with same zxid, expect same # logs as snaps to be kept
        assertEquals(numberOfSnapFilesToKeep + numberOfSnapFilesToKeep, dataLogDirVersion2.listFiles().length);
    }

    private File createDataDirLogFile(File version_2, int Zxid) throws IOException {
        File logFile = new File(version_2 + "/log." + Long.toHexString(Zxid));
        assertTrue(logFile.createNewFile(), "Failed to create log File:" + logFile.toString());
        return logFile;
    }

    private void createDataDirFiles(AtomicInteger offset, int limit, boolean createPrecedingLogFile, File version_2, List<File> snaps, List<File> logs) throws IOException {
        int counter = offset.get() + (2 * limit);
        if (createPrecedingLogFile) {
            counter++;
        }
        offset.set(counter);
        for (int i = 0; i < limit; i++) {
            // simulate log file
            logs.add(createDataDirLogFile(version_2, --counter));
            // simulate snapshot file
            File snapFile = new File(version_2 + "/snapshot." + Long.toHexString(--counter));
            assertTrue(snapFile.createNewFile(), "Failed to create snap File:" + snapFile.toString());
            snaps.add(snapFile);
        }
        if (createPrecedingLogFile) {
            logs.add(createDataDirLogFile(version_2, --counter));
        }
    }

    private void verifyFilesAfterPurge(List<File> logs, boolean exists) {
        for (File file : logs) {
            assertEquals(exists, file.exists(), "After purging, file " + file);
        }
    }

    private List<String> manyClientOps(final ZooKeeper zk, final CountDownLatch doPurge, int thCount, final String prefix) {
        Thread[] ths = new Thread[thCount];
        final List<String> znodes = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch finished = new CountDownLatch(thCount);
        final AtomicReference<Exception> exception = new AtomicReference<>();
        for (int indx = 0; indx < thCount; indx++) {
            final String myprefix = prefix + "-" + indx;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 750; i++) {
                    try {
                        String mynode = myprefix + "-" + i;
                        znodes.add(mynode);
                        zk.create(mynode, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    } catch (Exception e) {
                        LOG.error("Unexpected exception during ZkClient ops", e);
                        exception.set(e);
                    }
                    if (i == 200) {
                        doPurge.countDown();
                    }
                }
                finished.countDown();
            });
            ths[indx] = th;
        }

        for (Thread thread : ths) {
            thread.start();
        }
        try {
            boolean operationsFinishedSuccessfully = finished.await(OP_TIMEOUT_IN_MILLIS, TimeUnit.MILLISECONDS);
            if (exception.get() != null) {
                LOG.error("unexpected exception during running ZkClient ops:", exception.get());
                fail("unexpected exception during running ZkClient ops, see in the logs above");
            }
            assertTrue(operationsFinishedSuccessfully, "ZkClient ops not finished in time!");
        } catch (InterruptedException ie) {
            LOG.error("Unexpected exception", ie);
            fail("Unexpected exception occurred!");
        }
        return znodes;
    }

    private void makeValidSnapshot(File snapFile) throws IOException {
        SnapStream.setStreamMode(SnapStream.StreamMode.CHECKED);
        CheckedOutputStream os = SnapStream.getOutputStream(snapFile, true);
        OutputArchive oa = BinaryOutputArchive.getArchive(os);
        FileHeader header = new FileHeader(FileSnap.SNAP_MAGIC, 2, 1);
        header.serialize(oa, "fileheader");
        SnapStream.sealStream(os, oa);
        os.flush();
        os.close();

        assertTrue(SnapStream.isValidSnapshot(snapFile));
    }

    private void makeInvalidSnapshot(File snapFile) throws IOException {
        SnapStream.setStreamMode(SnapStream.StreamMode.CHECKED);
        OutputStream os = SnapStream.getOutputStream(snapFile, true);
        os.write(1);
        os.flush();
        os.close();

        assertFalse(SnapStream.isValidSnapshot(snapFile));
    }
}
