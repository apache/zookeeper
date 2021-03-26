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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.monitoring.BackupBean;
import org.apache.zookeeper.server.backup.monitoring.BackupStats;
import org.apache.zookeeper.server.backup.monitoring.TimetableBackupBean;
import org.apache.zookeeper.server.backup.monitoring.TimetableBackupMXBean;
import org.apache.zookeeper.server.backup.monitoring.TimetableBackupStats;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.timetable.TimetableBackup;
import org.apache.zookeeper.server.persistence.*;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.IntervalEndpoint;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;

/**
 * This class manages the backing up of txnlog and snap files to remote
 * storage for longer term and durable retention than is possible on
 * an ensemble server
 */
public class BackupManager {
  private static final Logger LOG = LoggerFactory.getLogger(BackupManager.class);

  private final Logger logger;
  private final File snapDir;
  private final File dataLogDir;
  private final File tmpDir;
  private final BackupConfig backupConfig;
  private final long backupIntervalInMilliseconds;
  private BackupProcess logBackup = null;
  private BackupProcess snapBackup = null;

  // backupStatus, backupLogZxid and backedupSnapZxid need to be access while synchronized
  // on backupStatus.
  private final BackupStatus backupStatus;
  private BackupPoint backupPoint;

  private final BackupStorageProvider backupStorage;
  private final long serverId;
  private final String namespace;

  @VisibleForTesting
  protected BackupBean backupBean = null;
  protected TimetableBackupBean timetableBackupBean = null;

  private BackupStats backupStats = null;

  // Optional timetable backup
  private BackupProcess timetableBackup = null;

  /**
   * Tracks a file that needs to be backed up, including temporary copies of the file
   */
  public static class BackupFile {
    private final File file;
    private final boolean isTemporary;
    private final ZxidRange zxidRange;

    /**
     * Create an instance of a BackupFile for the given initial file and zxid range
     * @param backupFile the initial/original file
     * @param isTemporaryFile whether the file is a temporary file
     * @param fileMinZxid the min zxid associated with this file
     * @param fileMaxZxid the max zxid associated with this file
     */
    public BackupFile(File backupFile, boolean isTemporaryFile, long fileMinZxid, long fileMaxZxid) {
      this(backupFile, isTemporaryFile, new ZxidRange(fileMinZxid, fileMaxZxid));
    }

    /**
     * Create an instance of a BackupFile for the given initial file and zxid range
     * @param backupFile the initial/original file
     * @param isTemporaryFile whether the file is a temporary file
     * @param zxidRange the zxid range associated with this file
     */
    public BackupFile(File backupFile, boolean isTemporaryFile, ZxidRange zxidRange) {
      Preconditions.checkNotNull(zxidRange);

      if (!zxidRange.isHighPresent()) {
        throw new IllegalArgumentException("ZxidRange must have a high value");
      }

      this.file = backupFile;
      this.isTemporary = isTemporaryFile;
      this.zxidRange = zxidRange;
    }

    /**
     * Perform cleanup including deleting temporary files.
     */
    public void cleanup() {
      if (isTemporary && exists()) {
        file.delete();
      }
    }

    /**
     * Whether the file representing the zxids exists
     * @return whether the file represented exists
     */
    public boolean exists() {
      return file != null && file.exists();
    }

    /**
     * Get the current file (topmost on the stack)
     * @return the current file
     */
    public File getFile() { return file; }

    /**
     * Get the zxid range associated with this file
     * @return the zxid range
     */
    public ZxidRange getZxidRange() {
      return zxidRange;
    }

    /**
     * Get the min zxid associated with this file
     * @return the min associated zxid
     */
    public long getMinZxid() { return zxidRange.getLow(); }

    /**
     * Get the max zxid associated with this file
     * @return the max associated zxid
     */
    public long getMaxZxid() { return zxidRange.getHigh(); }

    @Override
    public String toString() {
      return String.format("%s : %s : %d - %d",
          file == null ? "[empty]" : file.getPath(),
          isTemporary ? "temp" : "perm",
          zxidRange.getLow(),
          zxidRange.getHigh());
    }
  }

  /**
   * Implements txnlog specific logic for BackupProcess
   */
  protected class TxnLogBackup extends BackupProcess {
    private long iterationEndPoint;
    private final FileTxnSnapLog snapLog;

    /**
     * Constructor for TxnLogBackup
     * @param snapLog the FileTxnSnapLog object to use
     */
    public TxnLogBackup(FileTxnSnapLog snapLog) {
      super(LoggerFactory.getLogger(TxnLogBackup.class), BackupManager.this.backupStorage,
          backupIntervalInMilliseconds);
      this.snapLog = snapLog;
    }

    protected void initialize() throws IOException {
      backupStorage.cleanupInvalidFiles(null);

      BackupFileInfo latest =
          BackupUtil.getLatest(backupStorage, BackupFileType.TXNLOG, IntervalEndpoint.START);

      long rZxid = latest == null
          ? BackupUtil.INVALID_LOG_ZXID
          : latest.getIntervalEndpoint(IntervalEndpoint.END);

      logger.info("Latest Zxid from storage: {}  from status: {}",
          ZxidUtils.zxidToString(rZxid), ZxidUtils.zxidToString(backupPoint.getLogZxid()));

      if (rZxid != backupPoint.getLogZxid()) {
        synchronized (backupStatus) {
          backupPoint.setLogZxid(rZxid);
          backupStatus.update(backupPoint);
        }
      }
    }

    protected void startIteration() {
      // Store the current last logged zxid.  This becomes the stopping point
      // for the current iteration so we don't keep chasing our own tail as
      // new transactions get written.
      iterationEndPoint = snapLog.getLastLoggedZxid();
      backupStats.setTxnLogBackupIterationStart();
    }

    protected void endIteration(boolean errorFree) {
      backupStats.setTxnLogBackupIterationDone(errorFree);
      iterationEndPoint = 0L;
    }

    /**
     * Gets the next txnlog file to backup.  This is a temporary file created by copying
     * all transaction from the previous backup point until the end zxid for this iteration, or
     * a file indicating that some log records were lost.
     * @return the file that needs to be backed-up.  The minZxid is the first
     *      zxid contained in the file.  The maxZxid is the last zxid that is contained in the
     *      file.
     * @throws IOException
     */
    protected BackupFile getNextFileToBackup() throws IOException {
      long startingZxid = backupStatus.read().getLogZxid() + 1;

      // Don't keep chasing the tail so stop if past the last zxid at the time
      // this iteration started.
      if (startingZxid > iterationEndPoint) {
        return null;
      }

      TxnLog.TxnIterator iter = null;
      FileTxnLog newFile = null;
      long lastZxid = -1;
      int txnCopied = 0;
      BackupFile ret;

      logger.info("Creating backup file from zxid {}.", ZxidUtils.zxidToString(startingZxid));

      try {
        iter = snapLog.readTxnLog(startingZxid, true);

        // Use a temp directory to avoid conflicts with live txnlog files
        newFile = new FileTxnLog(tmpDir);

        // TODO: Ideally, we should have have a way to prevent lost TxLogs
        // Check for lost txnlogs; <=1 indicates that no backups have been done before so
        // nothing can be considered lost.
        // If a lost sequence is found then return a file whose name encodes the lost
        // sequence and back that up so the backup store has a record of the lost sequence
        if (startingZxid > 1 &&
            iter.getHeader() != null &&
            iter.getHeader().getZxid() > startingZxid) {

          logger.error("TxnLog backups lost.  Required starting zxid={}  First available zxid={}",
              ZxidUtils.zxidToString(startingZxid),
              ZxidUtils.zxidToString(iter.getHeader().getZxid()));

          String fileName = String.format("%s.%s",
              BackupUtil.LOST_LOG_PREFIX,
              Long.toHexString(startingZxid));
          File lostZxidFile = new File(tmpDir, fileName);
          lostZxidFile.createNewFile();

          return new BackupFile(lostZxidFile, true, startingZxid, iter.getHeader().getZxid() - 1);
        }

        while (iter.getHeader() != null) {
          TxnHeader hdr = iter.getHeader();

          if (hdr.getZxid() > iterationEndPoint) {
            break;
          }

          newFile.append(hdr, iter.getTxn());

          // update position and count only AFTER the record has been successfully
          // copied
          lastZxid = hdr.getZxid();
          txnCopied++;

          iter.next();
        }

        ret = makeBackupFileFromCopiedLog(newFile, lastZxid);

        if (ret != null) {
          logger.info("Copied {} records starting at {} and ending at zxid {}.",
              txnCopied,
              ZxidUtils.zxidToString(ret.getMinZxid()),
              ZxidUtils.zxidToString(ret.getMaxZxid()));
        }

      } catch (IOException e) {
        logger.warn("Hit exception after {} records.  Exception: {} ", txnCopied, e.fillInStackTrace());

        // If any records were copied return those and ignore the error.  Otherwise
        // rethrow the error to be handled by the caller as a failed backup iteration.
        if (txnCopied <= 0) {
          throw e;
        }

        ret = makeBackupFileFromCopiedLog(newFile, lastZxid);
      } finally {
        if (iter != null) {
          iter.close();
        }

        if (newFile != null) {
          newFile.close();
        }
      }

      return ret;
    }

    private BackupFile makeBackupFileFromCopiedLog(FileTxnLog backupTxnLog, long lastZxid) {

      if (backupTxnLog == null) {
        return null;
      }

      // The logFile gets initialized with the first transaction's zxid
      File logFile = backupTxnLog.getCurrentFile();

      if (logFile == null) {
        return null;
      }

      long firstZxid = Util.getZxidFromName(logFile.getName(), Util.TXLOG_PREFIX);

      if (lastZxid == -1) {
        lastZxid = firstZxid;
      }

      return new BackupFile(logFile, true, new ZxidRange(firstZxid, lastZxid));
    }

    protected void backupComplete(BackupFile file) throws IOException {
      synchronized (backupStatus) {
        backupPoint.setLogZxid(file.getMaxZxid());
        backupStatus.update(backupPoint);
      }

      logger.info("Updated backedup tnxlog zxid to {}",
          ZxidUtils.zxidToString(backupPoint.getLogZxid()));
    }
  }

  /**
   * Implements snapshot specific logic for BackupProcess
   */
  protected class SnapBackup extends BackupProcess {
    private final FileTxnSnapLog snapLog;
    private final List<BackupFile> filesToBackup = new ArrayList<>();

    /**
     * Constructor for SnapBackup
     * @param snapLog the FileTxnSnapLog object to use
     */
    public SnapBackup(FileTxnSnapLog snapLog) {
      super(LoggerFactory.getLogger(SnapBackup.class), BackupManager.this.backupStorage,
          backupIntervalInMilliseconds);
      this.snapLog = snapLog;
    }

    protected void initialize() throws IOException {
      backupStorage.cleanupInvalidFiles(null);

      BackupFileInfo latest =
          BackupUtil.getLatest(backupStorage, BackupFileType.SNAPSHOT, IntervalEndpoint.START);

      long rZxid = latest == null
          ? BackupUtil.INVALID_SNAP_ZXID
          : latest.getIntervalEndpoint(IntervalEndpoint.START);

      logger.info("Latest Zxid from storage: {}  from status: {}",
          ZxidUtils.zxidToString(rZxid), ZxidUtils.zxidToString(backupPoint.getSnapZxid()));

      if (rZxid != backupPoint.getSnapZxid()) {
        synchronized (backupStatus) {
          backupPoint.setSnapZxid(rZxid);
          backupStatus.update(backupPoint);
        }
      }
    }

    /**
     * Prepares snapshot files to back up and populate filesToBackup.
     * Note that this implementation persists all snapshots instead of only persisting the
     * latest snapshot.
     * @throws IOException
     */
    protected void startIteration() throws IOException {
      backupStats.setSnapshotBackupIterationStart();
      filesToBackup.clear();

      // Get all available snapshots excluding the ones whose lastProcessedZxid falls into the
      // zxid range [0, backedupSnapZxid]
      List<File> candidateSnapshots = snapLog.findValidSnapshots(0, backupPoint.getSnapZxid());
      // Sort candidateSnapshots from oldest to newest
      Collections.reverse(candidateSnapshots);

      if (candidateSnapshots.size() == 0) {
        // Either no snapshots or no newer snapshots to back up, so return
        return;
      }

      for (int i = 0; i < candidateSnapshots.size(); i++) {
        File f = candidateSnapshots.get(i);
        ZxidRange zxidRange = Util.getZxidRangeFromName(f.getName(), Util.SNAP_PREFIX);

        if (i == candidateSnapshots.size() - 1) {
          // This is the most recent snapshot

          // Handle backwards compatibility for snapshots that use old style naming where
          // only the starting zxid is included.
          // TODO: Can be removed after all snapshots being produced have ending zxid
          if (!zxidRange.isHighPresent()) {
            // Use the last logged zxid for the zxidRange for the latest snapshot as a best effort
            // approach
            // TODO: Because this is the best effort approach, the zxidRange will not be accurate
            // TODO: Consider rewriting these latest snapshots to backup storage if necessary
            // TODO: when we know the high zxid when we get a newer snapshot
            long latestZxid = snapLog.getLastLoggedZxid();
            long consistentAt = latestZxid == -1 ? zxidRange.getLow() : latestZxid;
            zxidRange = new ZxidRange(zxidRange.getLow(), consistentAt);
          }
        } else {
          // All newer snapshots that are not the most recent snapshot

          // Handle backwards compatibility for snapshots that use old style naming where
          // only the starting zxid is included.
          // TODO: Can be removed after all snapshots being produced have ending zxid
          if (!zxidRange.isHighPresent()) {
            // ZxidRange will be [low, high] where high will be the zxid right before the next
            // snapshot's lastProcessedZxid
            long nextSnapshotStartZxid =
                Util.getZxidFromName(candidateSnapshots.get(i + 1).getName(), Util.SNAP_PREFIX);
            zxidRange = new ZxidRange(zxidRange.getLow(), nextSnapshotStartZxid - 1);
          }
        }

        filesToBackup.add(new BackupFile(f, false, zxidRange));
      }
    }

    protected void endIteration(boolean errorFree) {
      backupStats.setSnapshotBackupIterationDone(errorFree);
      filesToBackup.clear();
    }

    protected BackupFile getNextFileToBackup() {
      if (filesToBackup.isEmpty()) {
        return null;
      }

      return filesToBackup.remove(0);
    }

    protected void backupComplete(BackupFile file) throws IOException {
      synchronized (backupStatus) {
        backupPoint.setSnapZxid(file.getMinZxid());
        backupStatus.update(backupPoint);
      }
      backupStats.incrementNumberOfSnapshotFilesBackedUpThisIteration();

      logger.info("Updated backedup snap zxid to {}",
          ZxidUtils.zxidToString(backupPoint.getSnapZxid()));
    }
  }

  /**
   * Constructor for the BackupManager.
   * @param snapDir the snapshot directory
   * @param dataLogDir the txnlog directory
   * @param serverId the id of the zk server
   * @param backupConfig the backup config object
   * @throws IOException
   */
  public BackupManager(File snapDir, File dataLogDir, long serverId, BackupConfig backupConfig)
      throws IOException {
    logger = LoggerFactory.getLogger(BackupManager.class);
    logger.info("snapDir={}", snapDir.getPath());
    logger.info("dataLogDir={}", dataLogDir.getPath());
    logger.info("backupStatusDir={}", backupConfig.getStatusDir().getPath());
    logger.info("tmpDir={}", backupConfig.getTmpDir().getPath());
    logger.info("backupIntervalInMinutes={}", backupConfig.getBackupIntervalInMinutes());
    logger.info("serverId={}", serverId);
    logger.info("namespace={}", backupConfig.getNamespace());

    this.snapDir = snapDir;
    this.dataLogDir = dataLogDir;
    this.backupConfig = backupConfig;
    this.tmpDir = backupConfig.getTmpDir();
    this.backupStatus = new BackupStatus(backupConfig.getStatusDir());
    this.backupIntervalInMilliseconds =
        TimeUnit.MINUTES.toMillis(backupConfig.getBackupIntervalInMinutes());
    this.serverId = serverId;
    this.namespace = backupConfig.getNamespace() == null ? "UNKNOWN" : backupConfig.getNamespace();
    try {
      backupStorage = createStorageProviderImpl(backupConfig);
    } catch (ReflectiveOperationException e) {
      throw new BackupException(e.getMessage(), e);
    }
    initialize();
  }

  /**
   * Start the backup processes.
   * @throws IOException
   */
  public synchronized void start() throws IOException {
    logger.info("BackupManager starting.");

    (new Thread(logBackup)).start();
    (new Thread(snapBackup)).start();
    if (timetableBackup != null) {
      (new Thread(timetableBackup)).start();
    }
  }

  /**
   * Stop the backup processes.
   */
  public void stop() {
    logger.info("BackupManager shutting down.");

    synchronized (this) {
      logBackup.shutdown();
      snapBackup.shutdown();
      logBackup = null;
      snapBackup = null;

      // Unregister MBeans so they can get GC-ed
      if (backupBean != null) {
        MBeanRegistry.getInstance().unregister(backupBean);
        backupBean = null;
      }
      if (timetableBackupBean != null) {
        MBeanRegistry.getInstance().unregister(timetableBackupBean);
        timetableBackupBean = null;
      }
    }
  }

  public BackupProcess getLogBackup() { return logBackup; }
  public BackupProcess getSnapBackup() { return snapBackup; }
  public BackupProcess getTimetableBackup() { return timetableBackup; }

  public long getBackedupLogZxid() {
    synchronized (backupStatus) {
      return backupPoint.getLogZxid();
    }
  }

  public long getBackedupSnapZxid() {
    synchronized (backupStatus) {
      return backupPoint.getSnapZxid();
    }
  }

  public synchronized void initialize() throws IOException {
    try {
      backupStats = new BackupStats();
      backupBean = new BackupBean(backupStats, serverId);
      MBeanRegistry.getInstance().register(backupBean, null);
      LOG.info("Registered Backup bean {} with JMX.", backupBean.getName());
    } catch (JMException e) {
      LOG.error("Failed to register Backup bean with JMX for namespace {} on server {}.", namespace,
          serverId, e);
      backupBean = null;
    }

    synchronized (backupStatus) {
      backupStatus.createIfNeeded();
      // backupPoint is only to be initialized once via backupStatus.read() in initialize()
      backupPoint = backupStatus.read();
    }

    if (!tmpDir.exists()) {
      if (!tmpDir.mkdirs()) {
        String errorMsg = "BackupManager::initialize(): failed to create tmpDir!";
        logger.error(errorMsg);
        throw new IOException(errorMsg);
      }
    }

    logBackup = new TxnLogBackup(new FileTxnSnapLog(dataLogDir, snapDir));
    snapBackup = new SnapBackup(new FileTxnSnapLog(dataLogDir, snapDir));

    logBackup.initialize();
    snapBackup.initialize();

    // if timetable backup is enabled, initialize it as well
    if (backupConfig.isTimetableEnabled()) {
      LOG.info("BackupManager::initialize(): timetable is enabled!");
      // Create a separate instance of BackupStorageProvider for timetable backup
      // This is because we want the timetable backup to be stored in a different storage path
      BackupStorageProvider timetableBackupStorage;
      try {
        timetableBackupStorage = createStorageProviderImpl(
            backupConfig.getBuilder().setBackupStoragePath(backupConfig.getTimetableStoragePath())
                .build().get());
        LOG.info(
            "BackupManager::initialize(): timetable backup storage initialized! Timetable storage path: "
                + backupConfig.getTimetableStoragePath());
      } catch (Exception e) {
        throw new BackupException(e.getMessage(), e);
      }

      // create metric-related classes for timetable backup
      TimetableBackupStats timetableBackupStats = new TimetableBackupStats();
      timetableBackupBean = new TimetableBackupBean(timetableBackupStats);
      try {
        // Put timetable backup bean under backup bean, so that they are coupled. this is
        // consistent with the backup-timetable design where backup must be enabled in order for
        // timetable to work
        if (backupBean != null) {
          MBeanRegistry.getInstance().register(timetableBackupBean, backupBean);
          LOG.info("BackupManager::initialize(): registered timetable backup bean {} with JMX.",
              backupBean.getName());
        } else {
          LOG.error("BackupManager::initialize(): Failed to register timetable backup bean with "
              + "JMX because parent BackupBean is null!");
        }
      } catch (JMException e) {
        LOG.error(
            "BackupManager::initialize(): Failed to register timetable backup bean with JMX for "
                + "namespace {} on server {}.", namespace, serverId, e);
      }

      // create an instance of TimetableBackup and initialize
      timetableBackup = new TimetableBackup(new FileTxnSnapLog(dataLogDir, snapDir), tmpDir,
          timetableBackupStorage, backupIntervalInMilliseconds,
          backupConfig.getTimetableBackupIntervalInMs(), backupStatus, backupPoint,
          timetableBackupStats);
      timetableBackup.initialize();
    }
  }

  /**
   * Instantiates the storage provider implementation by reflection. This allows the user to
   * choose which BackupStorageProvider implementation to use by specifying the fully-qualified
   * class name in BackupConfig (read from Properties).
   * @param backupConfig
   * @return
   * @throws ClassNotFoundException
   * @throws NoSuchMethodException
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   * @throws InstantiationException
   */
  private BackupStorageProvider createStorageProviderImpl(BackupConfig backupConfig)
      throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
             InvocationTargetException, InstantiationException {
    Class<?> clazz = Class.forName(backupConfig.getStorageProviderClassName());
    Constructor<?> constructor = clazz.getConstructor(BackupConfig.class);
    Object storageProvider = constructor.newInstance(backupConfig);
    return (BackupStorageProvider) storageProvider;
  }
}