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

package org.apache.zookeeper.server.backup;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.backup.monitoring.BackupBean;
import org.apache.zookeeper.server.backup.monitoring.TimetableBackupBean;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.impl.FileSystemBackupStorage;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests ZooKeeper backup-related metrics: BackupBean and TimetableBackupBean.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BackupBeanTest extends ZKTestCase {
  private static final Logger LOG = LoggerFactory.getLogger(BackupBeanTest.class);
  private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
  private static final int CONNECTION_TIMEOUT = 300000;
  private static final String TEST_NAMESPACE = "TEST_NAMESPACE";

  private ZooKeeper connection;
  private File dataDir;
  private File backupStatusDir;
  private File backupTmpDir;
  private File backupDir;
  private ZooKeeperServer zks;
  private ServerCnxnFactory serverCnxnFactory;
  private BackupStorageProvider backupStorage;
  private BackupStatus backupStatus;
  private FileTxnSnapLog snapLog;
  private BackupConfig backupConfig;

  @Before
  public void setup() throws Exception {
    dataDir = ClientBase.createTmpDir();
    backupStatusDir = ClientBase.createTmpDir();
    backupTmpDir = ClientBase.createTmpDir();
    backupDir = ClientBase.createTmpDir();

    backupConfig = new BackupConfig.Builder().
        setEnabled(true).
        setStatusDir(testBaseDir).
        setTmpDir(testBaseDir).
        setBackupStoragePath(backupDir.getAbsolutePath()).
        setNamespace(TEST_NAMESPACE).
        setStorageProviderClassName(FileSystemBackupStorage.class.getName()).
        setTimetableEnabled(true).
        setTimetableBackupIntervalInMs(100L). // 0.1s to ensure in-memory records are created
        setTimetableStoragePath(backupDir.getAbsolutePath()).
        build().get();
    backupStorage = new FileSystemBackupStorage(backupConfig);

    ClientBase.setupTestEnv();

    LOG.info("Starting Zk");

    zks = new ZooKeeperServer(dataDir, dataDir, 3000);
    SyncRequestProcessor.setSnapCount(100);
    final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
    serverCnxnFactory = ServerCnxnFactory.createFactory(PORT, -1);
    serverCnxnFactory.startup(zks);

    LOG.info("Waiting for server startup");

    Assert.assertTrue("waiting for server being up ",
        ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

    backupStatus = new BackupStatus(backupStatusDir);

    snapLog = new FileTxnSnapLog(dataDir, dataDir);

    connection = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, DummyWatcher.INSTANCE);
  }

  @After
  public void teardown() throws Exception {
    if (connection != null) {
      connection.close();
    }
    connection = null;

    LOG.info("Closing and cleaning up Zk");

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

    backupStatus = null;
    serverCnxnFactory = null;
    zks = null;
    snapLog = null;
    backupStorage = null;
  }

  @Test
  public void test1_MBeanRegistration() throws IOException {
    // Register MBean when initializing backup manager
    long serverId = 0L;
    BackupManager bm = new BackupManager(dataDir, dataDir, serverId, backupConfig);
    String expectedMBeanName = "Backup_id" + serverId;
    String expectedTimetableMBeanName =
        TimetableBackupBean.TIMETABLE_BACKUP_MBEAN_NAME;
    Set<ZKMBeanInfo> mbeans = MBeanRegistry.getInstance().getRegisteredBeans();
    Assert.assertTrue(containsMBean(mbeans, expectedMBeanName, false));
    Assert.assertTrue(containsMBean(mbeans, expectedTimetableMBeanName, false));

    // Unregister MBean when stopping backup manager
    bm.stop();
    mbeans = MBeanRegistry.getInstance().getRegisteredBeans();
    Assert.assertFalse(containsMBean(mbeans, expectedMBeanName, false));
    Assert.assertFalse(containsMBean(mbeans, expectedTimetableMBeanName, false));
  }

  private boolean containsMBean(Set<ZKMBeanInfo> mbeanSet, String mbeanName, boolean isHidden) {
    Optional<ZKMBeanInfo> foundMBean = mbeanSet.stream()
        .filter(mbean -> mbean.getName().equals(mbeanName) && mbean.isHidden() == isHidden)
        .findAny();
    return foundMBean.isPresent();
  }

  @Test
  public void test2_MBeanUpdate() throws Exception {
    MockBackupManager backupManager = new MockBackupManager(dataDir, dataDir, 0, backupConfig);
    BackupBean backupBean = backupManager.getBackupBean();

    Assert.assertEquals(0, backupBean.getMinutesSinceLastSuccessfulSnapshotIteration());
    Assert.assertEquals(0, backupBean.getMinutesSinceLastSuccessfulTxnLogIteration());

    String[] nodeNames = {"/firstNode", "/secondNode", "/thirdNode", "/fourthNode", "/fifthNode"};

    createNode(connection, nodeNames[0]);
    backupManager.getSnapBackup(snapLog, true).run(1);
    backupManager.getLogBackup(snapLog, true).run(1);
    Assert.assertTrue(backupBean.getLastSnapshotIterationDuration() > 0L);
    Assert.assertTrue(backupBean.getLastTxnLogIterationDuration() > 0L);
    Assert.assertEquals(1, backupBean.getNumberOfSnapshotFilesBackedUpLastIteration());
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedSnapshotIterations());
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedTxnLogIterations());
    Assert.assertFalse(backupBean.getSnapshotBackupActiveStatus());
    Assert.assertFalse(backupBean.getTxnLogBackupActiveStatus());

    createNode(connection, nodeNames[1]);
    backupManager.getSnapBackup(snapLog, false).run(1);
    backupManager.getLogBackup(snapLog, true).run(1);
    Assert.assertEquals(1, backupBean.getNumConsecutiveFailedSnapshotIterations());
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedTxnLogIterations());

    createNode(connection, nodeNames[2]);
    backupManager.getSnapBackup(snapLog, true).run(1);
    backupManager.getLogBackup(snapLog, false).run(1);
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedSnapshotIterations());
    Assert.assertEquals(1, backupBean.getNumConsecutiveFailedTxnLogIterations());

    createNode(connection, nodeNames[3]);
    backupManager.getSnapBackup(snapLog, false).run(1);
    backupManager.getLogBackup(snapLog, false).run(1);
    Assert.assertEquals(1, backupBean.getNumConsecutiveFailedSnapshotIterations());
    Assert.assertEquals(2, backupBean.getNumConsecutiveFailedTxnLogIterations());

    createNode(connection, nodeNames[4]);
    backupManager.getSnapBackup(snapLog, true).run(1);
    backupManager.getLogBackup(snapLog, true).run(1);
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedSnapshotIterations());
    Assert.assertEquals(0, backupBean.getNumConsecutiveFailedTxnLogIterations());

    // Wait one minute for metrics with minute granularity
    Thread.sleep(60 * 1000L);
    Assert.assertTrue(backupBean.getMinutesSinceLastSuccessfulSnapshotIteration() > 0L);
    Assert.assertTrue(backupBean.getMinutesSinceLastSuccessfulTxnLogIteration() > 0L);

    backupManager.stop();
  }

  @Test
  public void test3_TimetableBackupMBean() throws IOException, InterruptedException {
    MockBackupManager bm = new MockBackupManager(dataDir, dataDir, 0, backupConfig);
    bm.initialize();
    TimetableBackupBean timetableBackupBean = bm.getTimetableBackupBean();

    bm.getTimetableBackup().run(1);

    // Wait one minute for metrics with minute granularity
    Thread.sleep(60 * 1000L);
    long lastTimetableIterationDuration = timetableBackupBean.getLastTimetableIterationDuration();
    long minutesSinceLastSuccessfulTimetableIteration =
        timetableBackupBean.getMinutesSinceLastSuccessfulTimetableIteration();
    int numConsecutiveFailedTimetableIterations =
        timetableBackupBean.getNumConsecutiveFailedTimetableIterations();

    // Verify the metric values
    Assert.assertTrue(lastTimetableIterationDuration > 0L);
    Assert.assertTrue(minutesSinceLastSuccessfulTimetableIteration > 0L);
    Assert.assertEquals(0, numConsecutiveFailedTimetableIterations);

    bm.stop();
  }

  private void createNode(ZooKeeper zk, String path) throws Exception {
    zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
  }

  private static class MockBackupManager extends BackupManager {

    public MockBackupManager(File snapDir, File dataLogDir, long serverId,
        BackupConfig backupConfig) throws IOException {
      super(snapDir, dataLogDir, serverId, backupConfig);
    }

    public BackupBean getBackupBean() {
      return this.backupBean;
    }

    public TimetableBackupBean getTimetableBackupBean() {
      return this.timetableBackupBean;
    }

    public MockSnapshotBackupProcess getSnapBackup(FileTxnSnapLog snapLog, boolean errorFree) {
      return new MockSnapshotBackupProcess(snapLog, errorFree, backupBean);
    }

    public MockTxnLogBackupProcess getLogBackup(FileTxnSnapLog snapLog, boolean errorFree) {
      return new MockTxnLogBackupProcess(snapLog, errorFree, backupBean);
    }

    private class MockSnapshotBackupProcess extends BackupManager.SnapBackup {
      private final boolean expectedErrorFree;
      private final BackupBean backupBean;

      public MockSnapshotBackupProcess(FileTxnSnapLog snapLog, boolean expectedErrorFree,
          BackupBean backupBean) {
        super(snapLog);
        this.expectedErrorFree = expectedErrorFree;
        this.backupBean = backupBean;
      }

      @Override
      protected void endIteration(boolean errorFree) {
        Assert.assertTrue(backupBean.getSnapshotBackupActiveStatus());
        super.endIteration(expectedErrorFree);
      }
    }

    private class MockTxnLogBackupProcess extends BackupManager.TxnLogBackup {
      private final boolean expectedErrorFree;
      private final BackupBean backupBean;

      public MockTxnLogBackupProcess(FileTxnSnapLog snapLog, boolean expectedErrorFree,
          BackupBean backupBean) {
        super(snapLog);
        this.expectedErrorFree = expectedErrorFree;
        this.backupBean = backupBean;
      }

      @Override
      protected void endIteration(boolean errorFree) {
        Assert.assertTrue(backupBean.getTxnLogBackupActiveStatus());
        super.endIteration(expectedErrorFree);
      }
    }
  }
}
