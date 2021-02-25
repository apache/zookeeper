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
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.impl.FileSystemBackupStorage;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  public void testMBeanRegistration() throws IOException {
    // Register MBean when initializing backup manager
    BackupManager bm = new BackupManager(dataDir, dataDir, dataDir, backupTmpDir, 15,
        new FileSystemBackupStorage(backupConfig), TEST_NAMESPACE, 0);
    String expectedMBeanName = "Backup_" + TEST_NAMESPACE + ".server0";
    Set<ZKMBeanInfo> mbeans = MBeanRegistry.getInstance().getRegisteredBeans();
    Assert.assertTrue(containsMBean(mbeans, expectedMBeanName, false));

    // Unregister MBean when stopping backup manager
    bm.stop();
    mbeans = MBeanRegistry.getInstance().getRegisteredBeans();
    Assert.assertFalse(containsMBean(mbeans, expectedMBeanName, false));
  }

  private boolean containsMBean(Set<ZKMBeanInfo> mbeanSet, String mbeanName, boolean isHidden) {
    Optional<ZKMBeanInfo> foundMBean = mbeanSet.stream()
        .filter(mbean -> mbean.getName().equals(mbeanName) && mbean.isHidden() == isHidden)
        .findAny();
    return foundMBean.isPresent();
  }

  @Test
  public void testMBeanUpdate() throws Exception {
    MockBackupManager backupManager = new MockBackupManager(dataDir, dataDir, dataDir, backupTmpDir, 15,
        new FileSystemBackupStorage(backupConfig), TEST_NAMESPACE, 0);
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

    Thread.sleep(60 * 1000);
    Assert.assertTrue(backupBean.getMinutesSinceLastSuccessfulSnapshotIteration() > 0L);
    Assert.assertTrue(backupBean.getMinutesSinceLastSuccessfulTxnLogIteration() > 0L);
  }

  private void createNode(ZooKeeper zk, String path) throws Exception {
    zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
  }

  private static class MockBackupManager extends BackupManager {

    public MockBackupManager(File snapDir, File dataLogDir, File backupStatusDir, File tmpDir,
        int backupIntervalInMinutes, BackupStorageProvider backupStorageProvider, String namespace,
        long serverId) throws IOException {
      super(snapDir, dataLogDir, backupStatusDir, tmpDir, backupIntervalInMinutes,
          backupStorageProvider, namespace, serverId);
    }

    public BackupBean getBackupBean() {
      return this.backupBean;
    }

    public MockSnapshotBackupProcess getSnapBackup(FileTxnSnapLog snapLog, boolean errorFree) {
      return new MockSnapshotBackupProcess(snapLog, errorFree, backupBean);
    }

    public MockTxnLogBackupProcess getLogBackup(FileTxnSnapLog snapLog, boolean errorFree) {
      return new MockTxnLogBackupProcess(snapLog, errorFree, backupBean);
    }

    private class MockSnapshotBackupProcess extends BackupManager.SnapBackup {
      private boolean expectedErrorFree;
      private BackupBean backupBean;

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
      private boolean expectedErrorFree;
      private BackupBean backupBean;

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
