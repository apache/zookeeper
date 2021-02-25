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

import com.google.common.base.Preconditions;
import org.apache.commons.lang.NullArgumentException;
import org.apache.commons.lang.time.StopWatch;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.backup.monitoring.BackupBean;
import org.apache.zookeeper.server.backup.monitoring.BackupStats;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.persistence.*;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.ZxidPart;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
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
  private final int backupIntervalInMilliseconds;
  private BackupProcess logBackup = null;
  private BackupProcess snapBackup = null;

  // backupStatus, backupLogZxid and backedupSnapZxid need to be access while synchronized
  // on backupStatus.
  private final BackupStatus backupStatus;
  private long backedupLogZxid;
  private long backedupSnapZxid;

  private final BackupStorageProvider backupStorage;
  private final long serverId;
  private final String namespace;
  private BackupBean backupBean = null;
  private BackupStats backupStats = null;

  /**
   * Tracks a file that needs to be backed up, including temporary copies of the file
   */
  protected static class BackupFile {
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
   * Base class for the txnlog and snap back processes.
   * Provides the main backup loop and copying to remote storage (via HDFS APIs)
   */
  public abstract class BackupProcess implements Runnable {
    protected final Logger logger;
    private volatile boolean isRunning = true;

    /**
     * Initialize starting backup point based on remote storage and backupStatus file
     */
    protected abstract void initialize() throws IOException;

    /**
     * Marks the start of a backup iteration.  A backup iteration is run every
     * backup.interval.  This is called at the start of the iteration and before
     * any calls to getNextFileToBackup
     * @throws IOException
     */
    protected abstract void startIteration() throws IOException;

    /**
     * Marks the end of a backup iteration.  After this call there will be no more
     * calls to getNextFileToBackup or backupComplete until startIteration is
     * called again.
     * @param errorFree whether the iteration was error free
     * @throws IOException
     */
    protected abstract void endIteration(boolean errorFree);

    /**
     * Get the next file to backup
     * @return the next file to copy to backup storage.
     * @throws IOException
     */
    protected abstract BackupFile getNextFileToBackup() throws IOException;

    /**
     * Marks that the copy of the specified file to backup storage has completed
     * @param file the file to backup
     * @throws IOException
     */
    protected abstract void backupComplete(BackupFile file) throws IOException;

    /**
     * Create an instance of the backup process
     * @param logger the logger to use for this process.
     */
    public BackupProcess(Logger logger) {
      if (logger == null) {
        throw new NullArgumentException("logger");
      }

      this.logger = logger;
    }

    /**
     * Runs the main file based backup loop indefinitely.
     */
    public void run() {
      run(0);
    }

    /**
     * Runs the main file based backup loop the specified number of time.
     * Calls methods implemented by derived classes to get the next file to copy.
     */
    public void run(int iterations) {
      try {
        boolean errorFree = true;
        logger.debug("Thread starting.");

        while (isRunning) {
          BackupFile fileToCopy;
          StopWatch sw = new StopWatch();

          sw.start();

          try {
            if (logger.isDebugEnabled()) {
              logger.debug("Starting iteration");
            }

            // Cleanup any invalid backups that may have been left behind by the
            // previous failed iteration.
            // NOTE: Not done on first iteration (errorFree initialized to true) since
            //       initialize already does this.
            if (!errorFree) {
              backupStorage.cleanupInvalidFiles(null);
            }

            startIteration();

            while ((fileToCopy = getNextFileToBackup()) != null) {
              // Consider: compress file before sending to remote storage
              copyToRemoteStorage(fileToCopy);
              backupComplete(fileToCopy);
              fileToCopy.cleanup();
            }

            errorFree = true;
          } catch (IOException e) {
            errorFree = false;
            logger.warn("Exception hit during backup", e);
          }

          endIteration(errorFree);

          sw.stop();
          long elapsedTime = sw.getTime();

          logger.info("Completed backup iteration in {} milliseconds.  ErrorFree: {}.",
              elapsedTime, errorFree);

          if (iterations != 0) {
            iterations--;

            if (iterations < 1) {
              break;
            }
          }

          // Count elapsed time towards the backup interval
          long waitTime = backupIntervalInMilliseconds - elapsedTime;

          synchronized (this) {  // synchronized to get notification of termination
            if (waitTime > 0) {
              wait(waitTime);
            }
          }
        }
      } catch (InterruptedException e) {
        logger.warn("Interrupted exception while waiting for backup interval.", e.fillInStackTrace());
      } catch (Exception e) {
        logger.error("Hit unexpected exception", e.fillInStackTrace());
      }

      logger.warn("BackupProcess thread exited loop!");
    }

    /**
     * Copy given file to remote storage via HDFS APIs.
     * @param fileToCopy the file to copy
     * @throws IOException
     */
    private void copyToRemoteStorage(BackupFile fileToCopy) throws IOException {
      if (fileToCopy.getFile() == null) {
        return;
      }

      // Use the file name to encode the max included zxid
      String backedupName = BackupUtil.makeBackupName(
          fileToCopy.getFile().getName(), fileToCopy.getMaxZxid());

      backupStorage.copyToBackupStorage(fileToCopy.getFile(), new File(backedupName));
    }

    /**
     * Shutdown the backup process
     */
    public void shutdown() {
      synchronized (this) {
        isRunning = false;
        notifyAll();
      }
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
      super(LoggerFactory.getLogger(TxnLogBackup.class));
      this.snapLog = snapLog;
    }

    protected void initialize() throws IOException {
      backupStorage.cleanupInvalidFiles(null);

      BackupFileInfo latest =
          BackupUtil.getLatest(backupStorage, BackupFileType.TXNLOG, ZxidPart.MIN_ZXID);

      long rZxid = latest == null
          ? BackupUtil.INVALID_LOG_ZXID
          : latest.getZxid(ZxidPart.MAX_ZXID);

      logger.info("Latest Zxid from storage: {}  from status: {}",
          ZxidUtils.zxidToString(rZxid), ZxidUtils.zxidToString(backedupLogZxid));

      if (rZxid != backedupLogZxid) {
        synchronized (backupStatus) {
          backedupLogZxid = rZxid;
          backupStatus.update(backedupLogZxid, backedupSnapZxid);
        }
      }
    }

    protected void startIteration() {
      // Store the current last logged zxid.  This becomes the stopping point
      // for the current iteration so we don't keep chasing our own tail as
      // new transactions get written.
      iterationEndPoint = snapLog.getLastLoggedZxid();
    }

    protected void endIteration(boolean errorFree) {
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
        backedupLogZxid = file.getMaxZxid();
        backupStatus.update(backedupLogZxid, backedupSnapZxid);
      }

      logger.info("Updated backedup tnxlog zxid to {}", ZxidUtils.zxidToString(backedupLogZxid));
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
      super(LoggerFactory.getLogger(SnapBackup.class));
      this.snapLog = snapLog;
    }

    protected void initialize() throws IOException {
      backupStorage.cleanupInvalidFiles(null);

      BackupFileInfo latest =
          BackupUtil.getLatest(backupStorage, BackupFileType.SNAPSHOT, ZxidPart.MIN_ZXID);

      long rZxid = latest == null
          ? BackupUtil.INVALID_SNAP_ZXID
          : latest.getZxid(ZxidPart.MIN_ZXID);

      logger.info("Latest Zxid from storage: {}  from status: {}",
          ZxidUtils.zxidToString(rZxid), ZxidUtils.zxidToString(backedupLogZxid));

      if (rZxid != backedupSnapZxid) {
        synchronized (backupStatus) {
          backedupSnapZxid = rZxid;
          backupStatus.update(backedupLogZxid, backedupSnapZxid);
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
      filesToBackup.clear();

      // Get all available snapshots excluding the ones whose lastProcessedZxid falls into the
      // zxid range [0, backedupSnapZxid]
      List<File> candidateSnapshots = snapLog.findValidSnapshots(0, backedupSnapZxid);
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
        backedupSnapZxid = file.getMinZxid();
        backupStatus.update(backedupLogZxid, backedupSnapZxid);
      }

      logger.info("Updated backedup snap zxid to {}", ZxidUtils.zxidToString(backedupSnapZxid));
    }
  }

  /**
   * Constructor for the BackupManager.
   * @param snapDir the snapshot directory
   * @param dataLogDir the txnlog directory
   * @param backupStatusDir the backup status directory
   * @param tmpDir temporary directory
   * @param backupIntervalInMinutes the interval backups should run at in minutes
   * @param namespace the namespace of zk cluster to be backed up
   * @param serverId the id of the zk server
   * @throws IOException
   */
  public BackupManager(File snapDir, File dataLogDir, File backupStatusDir, File tmpDir,
      int backupIntervalInMinutes, BackupStorageProvider backupStorageProvider, String namespace,
      long serverId) throws IOException {
    logger = LoggerFactory.getLogger(BackupManager.class);
    logger.info("snapDir={}", snapDir.getPath());
    logger.info("dataLogDir={}", dataLogDir.getPath());
    logger.info("backupStatusDir={}", backupStatusDir.getPath());
    logger.info("tmpDir={}", tmpDir.getPath());
    logger.info("backupIntervalInMinutes={}", backupIntervalInMinutes);
    logger.info("serverId={}", serverId);
    logger.info("namespace={}", namespace);

    this.snapDir = snapDir;
    this.dataLogDir = dataLogDir;
    this.tmpDir = tmpDir;
    this.backupStatus = new BackupStatus(backupStatusDir);
    this.backupIntervalInMilliseconds = backupIntervalInMinutes * 60 * 1000;
    this.backupStorage = backupStorageProvider;
    this.serverId = serverId;
    this.namespace = namespace == null ? "UNKNOWN" : namespace;
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
      if (backupBean != null) {
        MBeanRegistry.getInstance().unregister(backupBean);
        backupBean = null;
      }
    }
  }

  public BackupProcess getLogBackup() { return logBackup; }
  public BackupProcess getSnapBackup() { return snapBackup; }

  public long getBackedupLogZxid() {
    synchronized (backupStatus) {
      return backedupLogZxid;
    }
  }

  public long getBackedupSnapZxid() {
    synchronized (backupStatus) {
      return backedupSnapZxid;
    }
  }

  public synchronized void initialize() throws IOException {
    try {
      backupStats = new BackupStats();
      backupBean = new BackupBean(backupStats, namespace, serverId);
      MBeanRegistry.getInstance().register(backupBean, null);
      LOG.info("Registered Backup bean {} with JMX.", backupBean.getName());
    } catch (JMException e) {
      LOG.warn("Failed to register Backup bean with JMX for namespace {} on server {}.", namespace,
          serverId, e);
      backupBean = null;
    }

    synchronized (backupStatus) {
      backupStatus.createIfNeeded();
      BackupPoint bp = backupStatus.read();
      backedupLogZxid = bp.getLogZxid();
      backedupSnapZxid = bp.getSnapZxid();
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
  }
}