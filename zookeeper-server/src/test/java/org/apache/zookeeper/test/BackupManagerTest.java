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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.Range;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.backup.BackupConfig;
import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.BackupManager;
import org.apache.zookeeper.server.backup.BackupPoint;
import org.apache.zookeeper.server.backup.BackupStatus;
import org.apache.zookeeper.server.backup.BackupUtil;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.impl.FileSystemBackupStorage;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests BackupManager. This class is intended to be in the package "org.apache.zookeeper.test" to
 * leverage other ZK testing components such as ClientBase.
 */
public class BackupManagerTest extends ZKTestCase implements Watcher {

  private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
  private static final int CONNECTION_TIMEOUT = 300000;
  private static final Logger LOG = LoggerFactory.getLogger(BackupManagerTest.class);
  private static final String TEST_EMPTY_NAMESPACE = "";

  private ZooKeeper connection;
  private File dataDir;
  private File backupStatusDir;
  private File backupTmpDir;
  private File backupDir;
  private ZooKeeperServer zks;
  private ServerCnxnFactory serverCnxnFactory;
  private BackupStorageProvider backupStorage;
  private BackupManager backupManager;
  private BackupStatus backupStatus;
  private FileTxnSnapLog snapLog;
  private int createdNodeCount = 0;
  private BackupConfig backupConfig;

  @Before
  public void setup() throws Exception {
    LOG.info("Setting up directories");

    dataDir = ClientBase.createTmpDir();
    backupStatusDir = ClientBase.createTmpDir();
    backupTmpDir = ClientBase.createTmpDir();
    backupDir = ClientBase.createTmpDir();

    // Create a dummy config
    backupConfig = new BackupConfig.Builder().
        setEnabled(true).
        setStatusDir(testBaseDir).
        setTmpDir(testBaseDir).
        setBackupStoragePath(backupDir.getAbsolutePath()).
        setNamespace(TEST_EMPTY_NAMESPACE).
        // Use FileSystemBackupStorage with a local path for testing
        setStorageProviderClassName(FileSystemBackupStorage.class.getName()).
        build().get();
    // Create BackupStorage
    backupStorage = new FileSystemBackupStorage(backupConfig);

    setupZk();
    connection = createConnection();
  }

  @After
  public void teardown() throws Exception {
    closeConnection(connection);
    connection = null;
    cleanupZk();
  }

  @Test
  public void testEmptyDirectory() throws Exception {
    File dataDir = ClientBase.createTmpDir();
    File backupTmpDir = ClientBase.createTmpDir();
    File backupDir = ClientBase.createTmpDir();

    BackupManager bm = new BackupManager(dataDir, dataDir, dataDir, backupTmpDir, 15,
        new FileSystemBackupStorage(backupConfig), null, -1);
    bm.initialize();

    bm.getLogBackup().run(1);
    bm.getSnapBackup().run(1);

    Assert.assertFalse("No files should have been backed up.", backupDir.list() == null);
    Assert.assertFalse("No temporary files should have been created.",
        backupTmpDir.list() == null);
    BackupStatus bs = new BackupStatus(dataDir);
    BackupPoint bp = bs.read();
    Assert.assertEquals("backupStatus should be set to min for log",
        bp.getLogZxid(), BackupUtil.INVALID_LOG_ZXID);
    Assert.assertEquals("backupStatus should be set to min for snap",
        bp.getSnapZxid(), BackupUtil.INVALID_SNAP_ZXID);
  }

  @Test
  public void testSwitchToNewBackupNode() throws Exception {
    createNodes(connection, 1000);
    backupManager.getLogBackup().run(1);
    backupManager.getSnapBackup().run(1);

    BackupPoint bp = backupStatus.read();
    String[] filesOrig = getBackupFiles(backupDir);
    Assert.assertEquals("backup status and backupmanager must match log",
        bp.getLogZxid(), backupManager.getBackedupLogZxid());
    Assert.assertEquals("backup status and backupmanager must match snap",
        bp.getSnapZxid(), backupManager.getBackedupSnapZxid());

    File backupStatusFile = new File(backupStatusDir, BackupStatus.STATUS_FILENAME);
    backupStatusFile.delete();

    backupManager.initialize();
    BackupPoint bp2 = backupStatus.read();
    Assert.assertEquals(
        "backup status and backupmanager must match log after switch to new backup node",
        bp2.getLogZxid(),
        backupManager.getBackedupLogZxid());
    Assert.assertEquals(
        "backup status and backupmanager must match snap after switch to new backup node",
        bp2.getSnapZxid(),
        backupManager.getBackedupSnapZxid());
    Assert.assertEquals("backup point must match prior backup point -- snap",
        bp.getLogZxid(), bp2.getLogZxid());
    Assert.assertEquals("backup point must match prior backup point -- snap",
        bp.getSnapZxid(), bp2.getSnapZxid());

    backupManager.getLogBackup().run(1);
    backupManager.getSnapBackup().run(1);

    String[] filesPost = getBackupFiles(backupDir);
    Assert.assertArrayEquals("files before and after switch to new backup node",
        filesOrig, filesPost);

    createNodes(connection, 1000);
    backupManager.getLogBackup().run(1);
    backupManager.getSnapBackup().run(1);

    String[] filesPostCreates = getBackupFiles(backupDir);
    List<Range<Long>> zxids = BackupUtil.getZxids(backupStorage, BackupFileType.TXNLOG);

    Assert.assertTrue("must have more backups", filesPostCreates.length > filesPost.length);

    for (int i = 0; i < zxids.size(); i++) {
      LOG.info("zxids[{}]: {}", i, zxids.get(i));
    }

    for (int i = 0; i < zxids.size() - 1; i++) {
      Assert.assertEquals("max of previous must be one less than next min",
          (zxids.get(i).upperEndpoint() + 1), zxids.get(i + 1).lowerEndpoint().longValue());
    }
  }

  @Test
  public void testBackupManager() throws Exception {
    backupManager.initialize();

    backupManager.getSnapBackup().run(1);

    String[] files = getBackupFiles(backupDir);
    Assert.assertTrue("Must have some snapshots", files != null);

    Assert.assertEquals("Must have just a single snapshot", 1, files.length);
    Assert.assertEquals("File must be called snapshot.0-0",
        Util.SNAP_PREFIX + ".0-0", files[0]);

    BackupPoint bp;

    createNodes(connection, 1);
    backupManager.getLogBackup().run(1);
    bp = backupStatus.read();

    Assert.assertEquals("Must have copied last txn",
        bp.getLogZxid(), zks.getTxnLogFactory().getLastLoggedZxid());
    List<Range<Long>> zxids = BackupUtil.getZxids(backupStorage, BackupFileType.TXNLOG);
    Range<Long> lastZxid = zxids.get(zxids.size() - 1);

    Assert.assertEquals("last log must be 1-2", lastZxid, Range.closed(1L, 2L));

    createNodes(connection, 1);
    backupManager.getLogBackup().run(1);
    zxids = BackupUtil.getZxids(backupStorage, BackupFileType.TXNLOG);
    int count = zxids.size();
    lastZxid = zxids.get(zxids.size() - 1);

    Assert.assertEquals("last log must be 3-3", lastZxid, Range.closed(3L, 3L));

    backupManager.getLogBackup().run(1);
    zxids = BackupUtil.getZxids(backupStorage, BackupFileType.TXNLOG);
    lastZxid = zxids.get(zxids.size() - 1);
    int count2 = zxids.size();

    Assert.assertEquals("last log must be 3-3", lastZxid, Range.closed(3L, 3L));
    Assert.assertEquals("no new files", count, count2);

    createNodes(connection, 1);
    backupManager.getLogBackup().run(1);
    zxids = BackupUtil.getZxids(backupStorage, BackupFileType.TXNLOG);
    lastZxid = zxids.get(zxids.size() - 1);

    Assert.assertEquals("last log must be 4-4", lastZxid, Range.closed(4L, 4L));

    createNodes(connection, 100);
    backupManager.getLogBackup().run(1);
    backupManager.getSnapBackup().run(1);

    FileTxnSnapLog snapLog = new FileTxnSnapLog(dataDir, dataDir);
    List<File> snaps = snapLog.findNRecentSnapshots(100);
    Collections.reverse(snaps);
    List<BackupFileInfo> backupFiles = BackupUtil.getBackupFiles(
        backupStorage,
        BackupFileType.SNAPSHOT,
        BackupUtil.ZxidPart.MIN_ZXID,
        BackupUtil.SortOrder.ASCENDING);

    Assert.assertEquals("must have only two snapshots", 2, backupFiles.size());
    long firstSnapZxid = Util.getZxidFromName(snaps.get(0).getName(), Util.SNAP_PREFIX);
    long firstBackupZxid = backupFiles.get(0).getZxidRange().lowerEndpoint();
    long lastSnapZxid = Util.getZxidFromName(
        snaps.get(snaps.size() - 1).getName(), Util.SNAP_PREFIX);
    long lastBackupZxid = backupFiles.get(1).getZxidRange().lowerEndpoint();

    Assert.assertEquals("first snapshot starting zxid must match",
        firstSnapZxid, firstBackupZxid);
    Assert.assertEquals("last snapshot starting zxid must match",
        lastSnapZxid, lastBackupZxid);
  }

  @Test
  public void restorabilityTest() throws Exception {
    String[] nodeNames = {"/firstNode", "/secondNode", "/thirdNode", "/fourthNode"};

    createNode(connection, nodeNames[0]);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);

    createNode(connection, nodeNames[1]);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);

    deleteNode(connection, nodeNames[1]);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);

    createNode(connection, nodeNames[2]);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);

    createNode(connection, nodeNames[3]);

    closeConnection(connection);

    boolean[][] expectedByLog = new boolean[][]{
        {true, false, false, false},
        {true, true, false, false},
        {true, false, false, false},
        {true, false, true, false}
    };

    for (int log = 0; log < 4; log++) {
      restoreAndValidate(0, log, nodeNames, expectedByLog[log]);
    }
  }

  @Test
  public void testBackupFileMerge() throws Exception {
    createNode(connection, "/fooBar");
    backupManager.getLogBackup().run(1);

    String[] backupFiles = getBackupFiles(backupDir);
    Assert.assertEquals("Should have one backed up file", 1, backupFiles.length);

    zks.getTxnLogFactory().rollLog();
    createNode(connection, "/foo1");
    long zxid1 = zks.getTxnLogFactory().getLastLoggedZxid();
    createNode(connection, "/foo2");

    zks.getTxnLogFactory().rollLog();
    createNode(connection, "/foo3");
    createNode(connection, "/foo4");
    zks.getTxnLogFactory().rollLog();
    createNode(connection, "/foo5");
    createNode(connection, "/foo6");

    File[] logs = snapLog.getSnapshotLogs(0);
    Assert.assertEquals("Should have four log files.", 4, logs.length);
    Assert.assertEquals("Should have zero additional txn in first file beyond backup point",
        backupManager.getBackedupLogZxid() + 1, zxid1);
    Assert.assertEquals("Zxid of second file must be backup point plus 1",
        backupManager.getBackedupLogZxid() + 1,
        Util.getZxidFromName(logs[1].getName(), Util.TXLOG_PREFIX));

    backupManager.getLogBackup().run(1);
    backupFiles = getBackupFiles(backupDir);

    Assert.assertEquals("Should have two backed up files even with multiple log files",
        2, backupFiles.length);
  }

  @Test
  public void testBackupWithNoNewRecords() throws Exception {
    createNode(connection, "/foo");
    backupManager.getLogBackup().run(1);
    long backedUpLog1 = backupManager.getBackedupLogZxid();
    backupManager.getLogBackup().run(1);
    Assert.assertEquals("Backup point cannot have moved #1",
        backedUpLog1, backupManager.getBackedupLogZxid());
    backupManager.getLogBackup().run(1);
    Assert.assertEquals("Backup point cannot have moved #2",
        backedUpLog1, backupManager.getBackedupLogZxid());
  }

  @Test
  public void testLostLogs() throws Exception {
    createNode(connection, "/foo");
    backupManager.getLogBackup().run(1);
    createNode(connection, "/foobar");
    createNode(connection, "/foobar2");
    zks.getTxnLogFactory().rollLog();
    createNode(connection, "/foobar3");
    createNode(connection, "/foobar4");
    zks.getTxnLogFactory().rollLog();
    createNode(connection, "/foobar5");

    File[] logs = snapLog.getSnapshotLogs(0);

    for (int f = 0; f < logs.length; f++) {
      LOG.info("File {}: {}", f, logs[f].getPath());
    }

    Assert.assertEquals("Should have three log files", 3, logs.length);
    logs[0].delete();
    logs[1].delete();
    backupManager.getLogBackup().run(1);
    backupManager.getLogBackup().run(1);
    backupManager.getLogBackup().run(1);

    String[] backupFiles = getBackupFiles(backupDir);
    Assert.assertEquals("Should have three files in backup dir", 3, backupFiles.length);

    boolean lostLogsFileFound = false;
    for (String s : backupFiles) {
      LOG.info("backupFile: {}", s);
      if (s.equals("lostLogs.3-6")) {
        lostLogsFileFound = true;
        break;
      }
    }

    Assert.assertTrue("Must have a lostLogs.3-6 file", lostLogsFileFound);
  }

  @Test
  public void testCleanupOfInvalidFilesOnStartUp() throws Exception {
    createNodes(connection, 110);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);
    createNodes(connection, 110);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);
    createNodes(connection, 110);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);
    createNodes(connection, 110);
    backupManager.getSnapBackup().run(1);
    backupManager.getLogBackup().run(1);

    String[] expectedBackupFiles = getBackupFiles(backupDir);

    backupManager.initialize();

    String[] actualBackupFiles = getBackupFiles(backupDir);

    Assert.assertArrayEquals("no files should have been deleted",
        expectedBackupFiles, actualBackupFiles);

    // Add some invalid files
    File badFile = new File(
        backupDir,
        makeTmpName(BackupUtil.makeBackupName(Util.makeSnapshotName(1000), 1010)));
    badFile.createNewFile();

    badFile = new File(
        backupDir,
        makeTmpName(BackupUtil.makeBackupName(Util.makeSnapshotName(1100), 1110)));
    badFile.createNewFile();

    badFile = new File(
        backupDir,
        makeTmpName(BackupUtil.makeBackupName(Util.makeLogName(1000), 1200)));
    badFile.createNewFile();

    backupManager.initialize();

    actualBackupFiles = getBackupFiles(backupDir);

    Assert.assertArrayEquals("bad files were should have been deleted",
        expectedBackupFiles, actualBackupFiles);
  }

  private String makeTmpName(String filename) {
    return "TMP_" + filename;
  }

  private void restoreAndValidate(int startingSnap, int endIndex, String[] nodeNames, boolean[] expectedExistence) throws Exception {
    ZooKeeper c = null;

    try {
      c = closeRestoreRestartGetConnection(startingSnap, endIndex);

      for (int n = 0; n < nodeNames.length && n < expectedExistence.length; n++) {
        String msg = "Node " + nodeNames[n] + " existence after restore from snap "
            + startingSnap + " and ending with log " + endIndex + ".";
        Assert.assertEquals(msg, expectedExistence[n], exists(c, nodeNames[n]));
        LOG.info("PASSED: " + msg);
      }
    } finally {
      closeConnection(c);
    }
  }

  private ZooKeeper closeRestoreRestartGetConnection(int startingSnap, int endIndex) throws Exception {
    closeZk();
    deleteDataFiles();
    restoreFromBackup(startingSnap, endIndex);
    startZk();
    return createConnection();
  }

  private void deleteDataFiles() {
    for (File f : snapLog.getSnapDir().listFiles()) {
      LOG.info("Deleting file {}", f.getPath());
      f.delete();
    }
    for (File f : snapLog.getDataDir().listFiles()) {
      LOG.info("Deleting file {}", f.getPath());
      f.delete();
    }
  }

  private void restoreFromBackup(int startingSnap, int endPoint) throws Exception {
    LOG.info("Restoring from backup: Snap {}  Log {}", startingSnap, endPoint);

    List<BackupFileInfo> snaps = BackupUtil.getBackupFilesByMin(backupStorage, BackupFileType.SNAPSHOT);
    List<BackupFileInfo> logs = BackupUtil.getBackupFilesByMin(backupStorage, BackupFileType.TXNLOG);

    copyToLocal(snaps.get(startingSnap), snapLog.getSnapDir());

    for (int i = startingSnap; i <= endPoint; i++) {
      copyToLocal(logs.get(i), snapLog.getDataDir());
    }
  }

  private void copyToLocal(BackupFileInfo src, File destDir) throws IOException {
    String name = src.getStandardFile().getName();
    File dest = new File(destDir, name);

    LOG.info("Copying from {} to {}", src.getBackedUpFile(), dest);
    backupStorage.copyToLocalStorage(src.getBackedUpFile(), dest);
  }

  private void setupZk() throws Exception {
    ClientBase.setupTestEnv();
    startZk();
    snapLog = new FileTxnSnapLog(dataDir, dataDir);
  }

  private void startZk() throws Exception {
    LOG.info("Starting Zk");

    zks = new ZooKeeperServer(dataDir, dataDir, 3000);
    SyncRequestProcessor.setSnapCount(100);
    final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
    serverCnxnFactory = ServerCnxnFactory.createFactory(PORT, -1);
    serverCnxnFactory.startup(zks);

    LOG.info("Waiting for server startup");

    Assert.assertTrue("waiting for server being up ",
        ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

    backupManager = new BackupManager(dataDir, dataDir, backupStatusDir, backupTmpDir, 15,
        backupStorage, null, -1);
    backupManager.initialize();
    backupStatus = new BackupStatus(backupStatusDir);
  }

  private void closeZk() throws Exception {
    LOG.info("Closing Zk");

    if (serverCnxnFactory != null) {
      serverCnxnFactory.closeAll(ServerCnxn.DisconnectReason.SERVER_SHUTDOWN);
      serverCnxnFactory.shutdown();
      serverCnxnFactory = null;
    }

    if (zks != null) {
      zks.getZKDatabase().close();
      zks.shutdown();
      zks = null;
    }

    Assert.assertTrue("waiting for server to shutdown",
        ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
  }

  private void cleanupZk() throws Exception {
    LOG.info("Closing and cleaning up Zk");

    closeZk();
    backupManager = null;
    backupStatus = null;
    serverCnxnFactory = null;
    zks = null;
    snapLog = null;
  }

  private ZooKeeper createConnection() throws IOException {
    return new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
  }

  private void closeConnection(ZooKeeper zk) throws InterruptedException {
    if (zk != null) {
      zk.close();
    }
  }

  private void createNodes(ZooKeeper zk, int count) throws Exception {
    int last = createdNodeCount + count;
    for (; createdNodeCount < last; createdNodeCount++) {
      zk.create("/invalidsnap-" + createdNodeCount, new byte[0], Ids.OPEN_ACL_UNSAFE,
          CreateMode.PERSISTENT);
    }
  }

  private void createNode(ZooKeeper zk, String path) throws Exception {
    zk.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
  }

  private void deleteNode(ZooKeeper zk, String path) throws Exception {
    zk.delete(path, -1);
  }

  private boolean exists(ZooKeeper zk, String path) throws Exception {
    return zk.exists(path, false) != null;
  }

  private String[] getBackupFiles(File path) {
    FilenameFilter filter = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.startsWith(Util.SNAP_PREFIX)
            || name.startsWith(Util.TXLOG_PREFIX)
            || name.startsWith(BackupUtil.LOST_LOG_PREFIX);
      }
    };

    return path.list(filter);
  }

  public void process(WatchedEvent event) {
    // do nothing
  }
}