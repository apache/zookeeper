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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerWatcher;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.TestUtils;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
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
        assertTrue(fileTxnSnapLog.getDataLogDir().exists());
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

    private static final byte[] TEST_DATA = "foo".getBytes();

    private static final class ReplayTxn {

        private final TxnHeader header;
        private final Record record;

        ReplayTxn(long zxid, int type, Record record) {
            this.header = new TxnHeader(1, 2, zxid, 2, type);
            this.record = record;
        }

        void applyTo(DataTree dataTree) {
            dataTree.processTxn(header, record);
        }
    }

    private static ReplayTxn createTxn(long zxid, String path, List<ACL> acl) {
        return new ReplayTxn(zxid, ZooDefs.OpCode.create, new CreateTxn(path, TEST_DATA, acl, false, -1));
    }

    private static ReplayTxn deleteTxn(long zxid, String path) {
        return new ReplayTxn(zxid, ZooDefs.OpCode.delete, new DeleteTxn(path));
    }

    private static ReplayTxn setDataTxn(long zxid, String path) {
        return new ReplayTxn(zxid, ZooDefs.OpCode.setData, new SetDataTxn(path, TEST_DATA, 1));
    }

    /**
     * Simulates a fuzzy snapshot: the ACL cache is serialized when the
     * snapshot starts, and the nodes are serialized when it finishes, so
     * transactions applied in between can leave nodes referencing ACL ids
     * which are absent from the serialized cache.
     */
    private static final class FuzzySnapshot {

        private final DataTree dataTree;
        private final File file;
        private final FileOutputStream outputStream;
        private final OutputArchive outputArchive;

        private FuzzySnapshot(DataTree dataTree) throws IOException {
            this.dataTree = dataTree;
            this.file = File.createTempFile("snapshot", "zk");
            this.outputStream = new FileOutputStream(file);
            this.outputArchive = BinaryOutputArchive.getArchive(outputStream);
        }

        static FuzzySnapshot start(DataTree dataTree) throws IOException {
            FuzzySnapshot snapshot = new FuzzySnapshot(dataTree);
            dataTree.serializeAcls(snapshot.outputArchive);
            return snapshot;
        }

        DataTree finishAndRestore() throws IOException {
            dataTree.serializeNodes(outputArchive);
            outputStream.close();

            DataTree restored = new DataTree();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                InputArchive inputArchive = BinaryInputArchive.getArchive(inputStream);
                restored.deserialize(inputArchive, "tree");
            }
            return restored;
        }
    }

    private static List<ACL> concatAcl(List<ACL> first, List<ACL> second) {
        List<ACL> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static void assertAcl(DataTree dataTree, String path, List<ACL> expected) {
        DataNode node = dataTree.getNode(path);
        assertNotNull(node);
        assertEquals(expected, dataTree.getACL(node));
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
        DataTree leader = new DataTree();
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn create = createTxn(2, "/a1", ZooDefs.Ids.CREATOR_ALL_ACL);
        create.applyTo(leader);

        DataTree restored = snapshot.finishAndRestore();
        create.applyTo(restored);

        assertAcl(leader, "/a1", ZooDefs.Ids.CREATOR_ALL_ACL);
        // ACL ids are server-local, so resolve the restored tree's own node.
        assertAcl(restored, "/a1", ZooDefs.Ids.CREATOR_ALL_ACL);
    }

    /**
     * ACL ids referenced by a fuzzy snapshot must not be re-issued to other
     * ACL lists while the transactions of the fuzzy range are replayed.
     *
     * A node can be serialized with a reference to an ACL id which was
     * interned only after the ACL cache had been serialized. Such an id is
     * missing from the deserialized cache, so it is not accounted for in
     * aclIndex. If the id is re-issued to a different ACL list during replay,
     * a replayed delete txn of an unrelated node still referencing the id
     * decrements a reference count that node never contributed to, and the
     * entry is garbage collected while live nodes still point to it.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testAclIdsReferencedBySnapshotAreNotReissuedDuringReplay() throws IOException {
        DataTree leader = new DataTree();

        // Leave a gap between aclIndex and the highest live ACL id.
        createTxn(2, "/m", ZooDefs.Ids.CREATOR_ALL_ACL).applyTo(leader);
        List<ACL> discardedAcl = concatAcl(ZooDefs.Ids.CREATOR_ALL_ACL, ZooDefs.Ids.OPEN_ACL_UNSAFE);
        createTxn(3, "/gone", discardedAcl).applyTo(leader);
        deleteTxn(4, "/gone").applyTo(leader);

        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        // These ACL ids are absent from the serialized cache but referenced by
        // the serialized nodes. Before the fix, replay re-issued /m's stale id
        // to /b; deleting /m then garbage-collected /b's live ACL entry.
        List<ACL> aclB = concatAcl(ZooDefs.Ids.READ_ACL_UNSAFE, ZooDefs.Ids.CREATOR_ALL_ACL);
        ReplayTxn createA = createTxn(5, "/a", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        ReplayTxn createB = createTxn(6, "/b", aclB);
        ReplayTxn deleteM = deleteTxn(7, "/m");
        ReplayTxn recreateM = createTxn(8, "/m", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        createA.applyTo(leader);
        createB.applyTo(leader);
        deleteM.applyTo(leader);
        recreateM.applyTo(leader);

        DataTree restored = snapshot.finishAndRestore();
        createA.applyTo(restored);
        createB.applyTo(restored);
        deleteM.applyTo(restored);
        recreateM.applyTo(restored);

        for (DataTree dataTree : new DataTree[]{leader, restored}) {
            assertAcl(dataTree, "/a", ZooDefs.Ids.OPEN_ACL_UNSAFE);
            assertAcl(dataTree, "/b", aclB);
            assertAcl(dataTree, "/m", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        }
        assertEquals(leader.aclCacheSize(), restored.aclCacheSize());
    }

    /**
     * Replaying a delete txn on top of a fuzzy snapshot which contains the
     * re-created node with a dangling ACL reference must not crash the
     * restore.
     *
     * The eager ACL fetch for watch triggering (ZOOKEEPER-4799) made such
     * dangling references fatal in deleteNode; the reference is repaired only
     * when the re-creating txn is replayed later (ZOOKEEPER-4846), so the
     * delete has to tolerate it.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testDeleteTxnReplayOnDanglingAclDoesNotCrash() throws IOException {
        DataTree leader = new DataTree();
        createTxn(2, "/x", ZooDefs.Ids.CREATOR_ALL_ACL).applyTo(leader);
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn delete = deleteTxn(3, "/x");
        ReplayTxn recreate = createTxn(4, "/x", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        delete.applyTo(leader);
        recreate.applyTo(leader);

        // The snapshot contains the re-created /x but not its ACL. Replay
        // first applies the old incarnation's delete to that dangling node.
        DataTree restored = snapshot.finishAndRestore();
        delete.applyTo(restored);
        recreate.applyTo(restored);

        assertAcl(restored, "/x", ZooDefs.Ids.OPEN_ACL_UNSAFE);
    }

    /**
     * Replaying a setData txn of an earlier incarnation on top of a fuzzy
     * snapshot which contains the re-created node with a dangling ACL
     * reference must not crash the restore.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testSetDataTxnReplayOnDanglingAclDoesNotCrash() throws IOException {
        DataTree leader = new DataTree();
        createTxn(2, "/x", ZooDefs.Ids.CREATOR_ALL_ACL).applyTo(leader);
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn setData = setDataTxn(3, "/x");
        ReplayTxn delete = deleteTxn(4, "/x");
        ReplayTxn recreate = createTxn(5, "/x", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        setData.applyTo(leader);
        delete.applyTo(leader);
        recreate.applyTo(leader);

        // Replay first applies the old incarnation's setData to the re-created
        // /x whose ACL is absent from the serialized cache.
        DataTree restored = snapshot.finishAndRestore();
        setData.applyTo(restored);
        delete.applyTo(restored);
        recreate.applyTo(restored);

        assertAcl(restored, "/x", ZooDefs.Ids.OPEN_ACL_UNSAFE);
    }

    /**
     * Watch events for a node whose ACL reference is missing from the ACL
     * cache are filtered as unreadable (fail closed), not delivered without
     * an ACL check: delivering them without a check would reintroduce
     * CVE-2024-23944 (see ZOOKEEPER-4799) for such nodes.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testWatchEventsForDanglingAclFailClosed() throws IOException {
        DataTree leader = new DataTree();
        createTxn(2, "/x", ZooDefs.Ids.CREATOR_ALL_ACL).applyTo(leader);
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn delete = deleteTxn(3, "/x");
        ReplayTxn recreate = createTxn(4, "/x", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        delete.applyTo(leader);
        recreate.applyTo(leader);
        DataTree restored = snapshot.finishAndRestore();

        List<List<ACL>> deliveredAcls = new ArrayList<>();
        ServerWatcher watcher = new ServerWatcher() {
            @Override
            public void process(WatchedEvent event) {
            }

            @Override
            public void process(WatchedEvent event, List<ACL> znodeAcl) {
                deliveredAcls.add(znodeAcl);
            }
        };
        restored.addWatch("/x", watcher, AddWatchMode.PERSISTENT.getMode());

        // Replaying the old incarnation's delete fires a watch event while
        // the snapshot node's ACL is still unknown.
        delete.applyTo(restored);

        assertFalse(deliveredAcls.isEmpty());
        for (List<ACL> acl : deliveredAcls) {
            assertNotNull(acl, "null ACLs pass checkACL");
            assertFalse(acl.isEmpty(), "empty ACLs pass checkACL");
            for (ACL entry : acl) {
                assertEquals(0, entry.getPerms() & ZooDefs.Perms.READ,
                    "watch events with an unknown ACL must fail closed: " + acl);
            }
        }
    }

    /**
     * Replaying a create txn whose parent has a dangling ACL reference must
     * not crash the restore.
     *
     * Same as above, but for the eager parent ACL fetch in createNode: the
     * parent is repaired only when its own re-creating txn is replayed, which
     * happens after the replay of create txns of an earlier incarnation's
     * children.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testCreateTxnReplayUnderDanglingAclParentDoesNotCrash() throws IOException {
        DataTree leader = new DataTree();
        createTxn(2, "/p", ZooDefs.Ids.CREATOR_ALL_ACL).applyTo(leader);
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn createChild = createTxn(3, "/p/c", ZooDefs.Ids.CREATOR_ALL_ACL);
        ReplayTxn deleteChild = deleteTxn(4, "/p/c");
        ReplayTxn deleteParent = deleteTxn(5, "/p");
        ReplayTxn recreateParent = createTxn(6, "/p", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        createChild.applyTo(leader);
        deleteChild.applyTo(leader);
        deleteParent.applyTo(leader);
        recreateParent.applyTo(leader);

        // The snapshot contains the re-created /p with a dangling ACL. Replay
        // first creates an old incarnation's child beneath that parent.
        DataTree restored = snapshot.finishAndRestore();
        createChild.applyTo(restored);
        deleteChild.applyTo(restored);
        deleteParent.applyTo(restored);
        recreateParent.applyTo(restored);

        assertAcl(restored, "/p", ZooDefs.Ids.OPEN_ACL_UNSAFE);
        assertNull(restored.getNode("/p/c"));
    }

    /**
     * Reference counting of ACLs interned during the fuzzy range works
     * correctly and entries are not removed prematurely.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testAclRefCountDuringFuzzySnapshotSync() throws IOException {
        DataTree leader = new DataTree();
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        ReplayTxn createA1 = createTxn(2, "/a1", ZooDefs.Ids.CREATOR_ALL_ACL);
        ReplayTxn createA2 = createTxn(3, "/a2", ZooDefs.Ids.CREATOR_ALL_ACL);
        ReplayTxn deleteA1 = deleteTxn(4, "/a1");
        createA1.applyTo(leader);
        createA2.applyTo(leader);
        deleteA1.applyTo(leader);

        DataTree restored = snapshot.finishAndRestore();
        createA1.applyTo(restored);
        createA2.applyTo(restored);
        deleteA1.applyTo(restored);

        assertAcl(leader, "/a2", ZooDefs.Ids.CREATOR_ALL_ACL);
        assertAcl(restored, "/a2", ZooDefs.Ids.CREATOR_ALL_ACL);
        assertEquals(leader.aclCacheSize(), restored.aclCacheSize());
    }

    /**
     * Nodes repaired during replay end up with the ACL values carried by
     * their create txns, also when the ids assigned during replay differ from
     * the ids recorded in the snapshot.
     *
     * The repair must be keyed by the ACL value in the txn, never by the
     * stale id recorded in the snapshot. Without id reservation, replay
     * assigns /a4's stale id to /a3's ACL, so a repair keyed on whether that
     * id exists in the cache (the approach of the original PR for this
     * issue) silently gives /a4 the ACL of /a3.
     *
     * See ZOOKEEPER-4689.
     */
    @Test
    public void testAclValuesMatchAfterFuzzySnapshotSyncReplay() throws IOException {
        DataTree leader = new DataTree();

        // Leave a gap between aclIndex and the serialized cache.
        createTxn(2, "/gone", ZooDefs.Ids.OPEN_ACL_UNSAFE).applyTo(leader);
        deleteTxn(3, "/gone").applyTo(leader);
        FuzzySnapshot snapshot = FuzzySnapshot.start(leader);

        List<ACL> otherAcl = concatAcl(ZooDefs.Ids.CREATOR_ALL_ACL, ZooDefs.Ids.READ_ACL_UNSAFE);
        ReplayTxn createA2 = createTxn(4, "/a2", ZooDefs.Ids.CREATOR_ALL_ACL);
        ReplayTxn createA3 = createTxn(5, "/a3", otherAcl);
        ReplayTxn createA4 = createTxn(6, "/a4", ZooDefs.Ids.CREATOR_ALL_ACL);
        createA2.applyTo(leader);
        createA3.applyTo(leader);
        createA4.applyTo(leader);

        DataTree restored = snapshot.finishAndRestore();
        createA2.applyTo(restored);
        createA3.applyTo(restored);
        createA4.applyTo(restored);

        for (DataTree dataTree : new DataTree[]{leader, restored}) {
            assertAcl(dataTree, "/a2", ZooDefs.Ids.CREATOR_ALL_ACL);
            assertAcl(dataTree, "/a3", otherAcl);
            assertAcl(dataTree, "/a4", ZooDefs.Ids.CREATOR_ALL_ACL);
        }
        assertEquals(leader.aclCacheSize(), restored.aclCacheSize());
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
        // set the flag to be the same as digestEnabled to make sure the last serialized data
        // (for example, datatree, digest, lastProcessedZxid) is setup as expected for backward
        // compatibility test.
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(digestEnabled);
        TxnHeader txnHeader = new TxnHeader(1, 1, 1, 1 + 1, ZooDefs.OpCode.create);
        CreateTxn txn = new CreateTxn("/" + 1, "data".getBytes(), null, false, 1);
        Request request = new Request(1, 1, 1, txnHeader, txn, 1);
        dataTree.processTxn(request.getHdr(), request.getTxn());
        snaplog.save(dataTree, sessions, true);

        int expectedNodeCount = dataTree.getNodeCount();
        ZooKeeperServer.setDigestEnabled(!digestEnabled);
        // set the flag to be the same as digestEnabled to make sure the last serialized data
        // (for example, datatree, digest, lastProcessedZxid) is setup as expected for backward
        // compatibility test.
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(!digestEnabled);
        snaplog.restore(dataTree, sessions, (hdr, rec, digest) -> {  });
        assertEquals(expectedNodeCount, dataTree.getNodeCount());
    }
}
