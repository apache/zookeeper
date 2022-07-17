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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.TestUtils;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FileTxnSnapLogTest {

    private File tmpDir;

    private File logDir;

    private File snapDir;

    private File logVersionDir;

    private File snapVersionDir;

    @BeforeEach
    public void setUp() throws Exception {
        tmpDir = ClientBase.createEmptyTestDir();
        logDir = new File(tmpDir, "logdir");
        snapDir = new File(tmpDir, "snapdir");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (tmpDir != null) {
            TestUtils.deleteFileRecursively(tmpDir);
        }
        this.tmpDir = null;
        this.logDir = null;
        this.snapDir = null;
        this.logVersionDir = null;
        this.snapVersionDir = null;
    }

    private File createVersionDir(File parentDir) {
        File versionDir = new File(parentDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        versionDir.mkdirs();
        return versionDir;
    }

    private void createLogFile(File dir, long zxid) throws IOException {
        File file = new File(dir.getPath() + File.separator + Util.makeLogName(zxid));
        file.createNewFile();
    }

    private void createSnapshotFile(File dir, long zxid) throws IOException {
        File file = new File(dir.getPath() + File.separator + Util.makeSnapshotName(zxid));
        file.createNewFile();
    }

    private void twoDirSetupWithCorrectFiles() throws IOException {
        logVersionDir = createVersionDir(logDir);
        snapVersionDir = createVersionDir(snapDir);

        // transaction log files in log dir
        createLogFile(logVersionDir, 1);
        createLogFile(logVersionDir, 2);

        // snapshot files in snap dir
        createSnapshotFile(snapVersionDir, 1);
        createSnapshotFile(snapVersionDir, 2);
    }

    private void singleDirSetupWithCorrectFiles() throws IOException {
        logVersionDir = createVersionDir(logDir);

        // transaction log and snapshot files in the same dir
        createLogFile(logVersionDir, 1);
        createLogFile(logVersionDir, 2);
        createSnapshotFile(logVersionDir, 1);
        createSnapshotFile(logVersionDir, 2);
    }

    private FileTxnSnapLog createFileTxnSnapLogWithNoAutoCreateDataDir(File logDir, File snapDir) throws IOException {
        return createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "false");
    }

    private FileTxnSnapLog createFileTxnSnapLogWithAutoCreateDataDir(
        File logDir,
        File snapDir,
        String autoCreateValue) throws IOException {
        String priorAutocreateDirValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, autoCreateValue);
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(logDir, snapDir);
        } finally {
            if (priorAutocreateDirValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, priorAutocreateDirValue);
            }
        }
        return fileTxnSnapLog;
    }

    private FileTxnSnapLog createFileTxnSnapLogWithAutoCreateDB(
        File logDir,
        File snapDir,
        String autoCreateValue) throws IOException {
        String priorAutocreateDBValue = System.getProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE);
        System.setProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE, autoCreateValue);
        FileTxnSnapLog fileTxnSnapLog;
        try {
            fileTxnSnapLog = new FileTxnSnapLog(logDir, snapDir);
        } finally {
            if (priorAutocreateDBValue == null) {
                System.clearProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE);
            } else {
                System.setProperty(FileTxnSnapLog.ZOOKEEPER_DB_AUTOCREATE, priorAutocreateDBValue);
            }
        }
        return fileTxnSnapLog;
    }

    /**
     * Test verifies the auto creation of log dir and snap dir.
     * Sets "zookeeper.datadir.autocreate" to true.
     */
    @Test
    public void testWithAutoCreateDataDir() throws IOException {
        assertFalse(logDir.exists(), "log directory already exists");
        assertFalse(snapDir.exists(), "snapshot directory already exists");

        FileTxnSnapLog fileTxnSnapLog = createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "true");

        assertTrue(logDir.exists());
        assertTrue(snapDir.exists());
        assertTrue(fileTxnSnapLog.getDataDir().exists());
        assertTrue(fileTxnSnapLog.getSnapDir().exists());
    }

    /**
     * Test verifies server should fail when log dir or snap dir doesn't exist.
     * Sets "zookeeper.datadir.autocreate" to false.
     */
    @Test
    public void testWithoutAutoCreateDataDir() throws Exception {
        assertThrows(FileTxnSnapLog.DatadirException.class, () -> {
            assertFalse(logDir.exists(), "log directory already exists");
            assertFalse(snapDir.exists(), "snapshot directory already exists");

            try {
                createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "false");
            } catch (FileTxnSnapLog.DatadirException e) {
                assertFalse(logDir.exists());
                assertFalse(snapDir.exists());
                // rethrow exception
                throw e;
            }
            fail("Expected exception from FileTxnSnapLog");
        });
    }

    private void attemptAutoCreateDB(
        File dataDir,
        File snapDir,
        Map<Long, Integer> sessions,
        String autoCreateValue,
        long expectedValue) throws IOException {
        sessions.clear();

        FileTxnSnapLog fileTxnSnapLog = createFileTxnSnapLogWithAutoCreateDB(dataDir, snapDir, autoCreateValue);

        long zxid = fileTxnSnapLog.restore(new DataTree(), sessions, new FileTxnSnapLog.PlayBackListener() {
            @Override
            public void onTxnLoaded(TxnHeader hdr, Record rec, TxnDigest digest) {
                // empty by default
            }
        });
        assertEquals(expectedValue, zxid, "unexpected zxid");
    }

    @Test
    public void testAutoCreateDB() throws IOException {
        assertTrue(logDir.mkdir(), "cannot create log directory");
        assertTrue(snapDir.mkdir(), "cannot create snapshot directory");
        File initFile = new File(logDir, "initialize");
        assertFalse(initFile.exists(), "initialize file already exists");

        Map<Long, Integer> sessions = new ConcurrentHashMap<>();

        attemptAutoCreateDB(logDir, snapDir, sessions, "false", -1L);
        attemptAutoCreateDB(logDir, snapDir, sessions, "true", 0L);

        assertTrue(initFile.createNewFile(), "cannot create initialize file");
        attemptAutoCreateDB(logDir, snapDir, sessions, "false", 0L);
    }

    @Test
    public void testGetTxnLogSyncElapsedTime() throws IOException {
        FileTxnSnapLog fileTxnSnapLog = createFileTxnSnapLogWithAutoCreateDataDir(logDir, snapDir, "true");

        TxnHeader hdr = new TxnHeader(1, 1, 1, 1, ZooDefs.OpCode.setData);
        Record txn = new SetDataTxn("/foo", new byte[0], 1);
        Request req = new Request(0, 0, 0, hdr, txn, 0);

        try {
            fileTxnSnapLog.append(req);
            fileTxnSnapLog.commit();
            long syncElapsedTime = fileTxnSnapLog.getTxnLogElapsedSyncTime();
            assertNotEquals(-1L, syncElapsedTime, "Did not update syncElapsedTime!");
        } finally {
            fileTxnSnapLog.close();
        }
    }

    @Test
    public void testDirCheckWithCorrectFiles() throws IOException {
        twoDirSetupWithCorrectFiles();

        try {
            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException | FileTxnSnapLog.SnapDirContentCheckException e) {
            fail("Should not throw ContentCheckException.");
        }
    }

    @Test
    public void testDirCheckWithSingleDirSetup() throws IOException {
        singleDirSetupWithCorrectFiles();

        try {
            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, logDir);
        } catch (FileTxnSnapLog.LogDirContentCheckException | FileTxnSnapLog.SnapDirContentCheckException e) {
            fail("Should not throw ContentCheckException.");
        }
    }

    @Test
    public void testDirCheckWithSnapFilesInLogDir() throws IOException {
        assertThrows(FileTxnSnapLog.LogDirContentCheckException.class, () -> {
            twoDirSetupWithCorrectFiles();

            // add snapshot files to the log version dir
            createSnapshotFile(logVersionDir, 3);
            createSnapshotFile(logVersionDir, 4);

            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
        });
    }

    @Test
    public void testDirCheckWithLogFilesInSnapDir() throws IOException {
        assertThrows(FileTxnSnapLog.SnapDirContentCheckException.class, () -> {
            twoDirSetupWithCorrectFiles();

            // add transaction log files to the snap version dir
            createLogFile(snapVersionDir, 3);
            createLogFile(snapVersionDir, 4);

            createFileTxnSnapLogWithNoAutoCreateDataDir(logDir, snapDir);
        });
    }

    /**
     * Make sure the ACL is exist in the ACL map after SNAP syncing.
     *
     * ZooKeeper uses ACL reference id and count to save the space in snapshot.
     * During fuzzy snapshot sync, the reference count may not be updated
     * correctly in case like the znode is already exist.
     *
     * When ACL reference count reaches 0, it will be deleted from the cache,
     * but actually there might be other nodes still using it. When visiting
     * a node with the deleted ACL id, it will be rejected because it doesn't
     * exist anymore.
     *
     * Here is the detailed flow for one of the scenario here:
     *   1. Server A starts to have snap sync with leader
     *   2. After serializing the ACL map to Server A, there is a txn T1 to
     *      create a node N1 with new ACL_1 which was not exist in ACL map
     *   3. On leader, after this txn, the ACL map will be ID1 -&gt; (ACL_1, COUNT: 1),
     *      and data tree N1 -&gt; ID1
     *   4. On server A, it will be empty ACL map, and N1 -&gt; ID1 in fuzzy snapshot
     *   5. When replaying the txn T1, it will skip at the beginning since the
     *      node is already exist, which leaves an empty ACL map, and N1 is
     *      referencing to a non-exist ACL ID1
     *   6. Node N1 will be not accessible because the ACL not exist, and if it
     *      became leader later then all the write requests will be rejected as
     *      well with marshalling error.
     */
    @Test
    public void testACLCreatedDuringFuzzySnapshotSync() throws IOException {
        DataTree leaderDataTree = new DataTree();

        // Start the simulated snap-sync by serializing ACL cache.
        File file = File.createTempFile("snapshot", "zk");
        FileOutputStream os = new FileOutputStream(file);
        OutputArchive oa = BinaryOutputArchive.getArchive(os);
        leaderDataTree.serializeAcls(oa);

        // Add couple of transaction in-between.
        TxnHeader hdr1 = new TxnHeader(1, 2, 2, 2, ZooDefs.OpCode.create);
        Record txn1 = new CreateTxn("/a1", "foo".getBytes(), ZooDefs.Ids.CREATOR_ALL_ACL, false, -1);
        leaderDataTree.processTxn(hdr1, txn1);

        // Finish the snapshot.
        leaderDataTree.serializeNodes(oa);
        os.close();

        // Simulate restore on follower and replay.
        FileInputStream is = new FileInputStream(file);
        InputArchive ia = BinaryInputArchive.getArchive(is);
        DataTree followerDataTree = new DataTree();
        followerDataTree.deserialize(ia, "tree");
        followerDataTree.processTxn(hdr1, txn1);

        DataNode a1 = leaderDataTree.getNode("/a1");
        assertNotNull(a1);
        assertEquals(ZooDefs.Ids.CREATOR_ALL_ACL, leaderDataTree.getACL(a1));

        assertEquals(ZooDefs.Ids.CREATOR_ALL_ACL, followerDataTree.getACL(a1));
    }

    @Test
    public void testEmptySnapshotSerialization() throws IOException {
        File dataDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog snaplog = new FileTxnSnapLog(dataDir, dataDir);
        DataTree dataTree = new DataTree();
        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();

        ZooKeeperServer.setDigestEnabled(true);
        snaplog.save(dataTree, sessions, true);
        snaplog.restore(dataTree, sessions, (hdr, rec, digest) -> {  });

        assertNull(dataTree.getDigestFromLoadedSnapshot());
    }

    @Test
    public void testSnapshotSerializationCompatibility() throws IOException {
        testSnapshotSerializationCompatibility(true, false);
        testSnapshotSerializationCompatibility(false, false);
        testSnapshotSerializationCompatibility(true, true);
        testSnapshotSerializationCompatibility(false, true);
    }

    void testSnapshotSerializationCompatibility(Boolean digestEnabled, Boolean snappyEnabled) throws IOException {
        File dataDir = ClientBase.createEmptyTestDir();
        FileTxnSnapLog snaplog = new FileTxnSnapLog(dataDir, dataDir);
        DataTree dataTree = new DataTree();
        ConcurrentHashMap<Long, Integer> sessions = new ConcurrentHashMap<>();
        SnapStream.setStreamMode(snappyEnabled ? SnapStream.StreamMode.SNAPPY : SnapStream.StreamMode.DEFAULT_MODE);

        ZooKeeperServer.setDigestEnabled(digestEnabled);
        TxnHeader txnHeader = new TxnHeader(1, 1, 1, 1 + 1, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/" + 1, "data".getBytes(), null, false, 1);
        Request request = new Request(1, 1, 1, txnHeader, txn, 1);
        dataTree.processTxn(request.getHdr(), request.getTxn());
        snaplog.save(dataTree, sessions, true);

        int expectedNodeCount = dataTree.getNodeCount();
        ZooKeeperServer.setDigestEnabled(!digestEnabled);
        snaplog.restore(dataTree, sessions, (hdr, rec, digest) -> {  });
        assertEquals(expectedNodeCount, dataTree.getNodeCount());
    }
}
