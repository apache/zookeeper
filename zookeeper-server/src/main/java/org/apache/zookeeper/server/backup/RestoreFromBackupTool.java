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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.ZxidPart;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.BackupStorageUtil;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  This is a tool to restore, from backup storage, a snapshot and set of transaction log files
 *  that combine to represent a transactionally consistent image of a ZooKeeper ensemble at
 *  some zxid.
 */
public class RestoreFromBackupTool {
  private static final Logger LOG = LoggerFactory.getLogger(RestoreFromBackupTool.class);

  BackupStorageProvider storage;
  FileTxnSnapLog snapLog;
  long zxidToRestore;
  boolean dryRun;
  File restoreTempDir;

  List<BackupFileInfo> logs;
  List<BackupFileInfo> snaps;
  List<BackupFileInfo> filesToCopy;

  int mostRecentLogNeededIndex;
  int snapNeededIndex;
  int oldestLogNeededIndex;

  /**
   * Default constructor; requires using parseArgs to setup state.
   */
  public RestoreFromBackupTool() {
    this(null, null, -1L, false, null);
  }

  /**
   * Constructor
   * @param storageProvider the backup provider from which to restore the backups
   * @param snapLog the snap and log provider to use
   * @param zxidToRestore the zxid upto which to restore, or Long.MAX to restore to the latest
   *                      available transactionally consistent zxid.
   * @param dryRun whether this is a dryrun in which case no files are actually copied
   * @param restoreTempDir a temporary, local (not remote) directory to stage the backup files from
   *                       remote backup storage for the processing stage of restoration. Note that
   *                       this directory and the files in it will be removed after restoration.
   */
  RestoreFromBackupTool(BackupStorageProvider storageProvider, FileTxnSnapLog snapLog,
      long zxidToRestore, boolean dryRun, File restoreTempDir) {

    filesToCopy = new ArrayList<>();
    snapNeededIndex = -1;
    mostRecentLogNeededIndex = -1;
    oldestLogNeededIndex = -1;

    this.storage = storageProvider;
    this.zxidToRestore = zxidToRestore;
    this.snapLog = snapLog;
    this.dryRun = dryRun;
    this.restoreTempDir = restoreTempDir;
  }

  /**
   * Attempts to perform a restore.
   */
  public void run() throws IOException {
    try {
      if (!findFilesToRestore()) {
        throw new IllegalArgumentException("Failed to find valid snapshot and logs to restore.");
      }

      if (snapLog == null || restoreTempDir == null || storage == null) {
        throw new BackupException(
            "The FileTxnSnapLog, RestoreTempDir and BackupStorageProvider cannot be null.");
      }

      File dataDir = snapLog.getDataDir();
      File snapDir = snapLog.getSnapDir();
      if (!dataDir.exists() && !dataDir.mkdirs()) {
        throw new BackupException("Failed to create a data directory at path: " + dataDir.getPath()
            + " to store restored txn logs.");
      }
      if (!snapDir.exists() && !snapDir.mkdirs()) {
        throw new BackupException("Failed to create a snap directory at path: " + snapDir.getPath()
            + " to store restored snapshot files.");
      }

      if (!dryRun) {
        if (!restoreTempDir.exists() && !restoreTempDir.mkdirs()) {
          throw new BackupException(
              "Failed to create a temporary directory at path: " + restoreTempDir.getPath()
                  + " to store copied backup files.");
        }

        // This step will create a "version-2" directory inside restoreTempDir,
        // all the selected backup files will be copied to version-2 directory
        FileTxnSnapLog restoreTempSnapLog =
            new FileTxnSnapLog(this.restoreTempDir, this.restoreTempDir);

        copyBackupFilesToLocalTempDir(restoreTempSnapLog);
        processCopiedBackupFiles(restoreTempSnapLog, zxidToRestore);
        copyProcessedRestoredFilesToDestination(restoreTempSnapLog);
      }
    } finally {
      if (restoreTempDir != null && restoreTempDir.exists()) {
        // Using recursive delete here because a "version-2" directory is created under restoreTempDir with FileTxnSnapLog
        BackupStorageUtil.deleteDirectoryRecursively(restoreTempDir);
      }
    }
  }

  /**
   * Finds the set of files (snapshot and txlog) that are required to restore to a transactionally
   * consistent point for the requested zxid.
   * Note that when restoring to the latest zxid, the transactionally consistent point may NOT
   * be the latest backed up zxid if logs have been lost in between; or if there is no
   * transactionally consistent point in which nothing will be restored but the restored will
   * be considered successful.
   * @return true if a transactionally consistent set of files could be found for the requested
   *         restore point; false in all other cases.
   * @throws IOException
   */
  private boolean findFilesToRestore() throws IOException {
    snaps = BackupUtil.getBackupFiles(storage, BackupFileType.SNAPSHOT, ZxidPart.MIN_ZXID,
        BackupUtil.SortOrder.DESCENDING);
    logs = BackupUtil.getBackupFiles(storage,
        new BackupFileType[]{BackupFileType.TXNLOG, BackupFileType.LOSTLOG}, ZxidPart.MIN_ZXID,
        BackupUtil.SortOrder.DESCENDING);
    filesToCopy = new ArrayList<>();

    snapNeededIndex = -1;

    while (findNextPossibleSnapshot()) {
      if (findLogRange()) {
        filesToCopy.add(snaps.get(snapNeededIndex));
        filesToCopy.addAll(logs.subList(mostRecentLogNeededIndex, oldestLogNeededIndex + 1));
        return true;
      }

      if (zxidToRestore != Long.MAX_VALUE) {
        break;
      }
    }

    // Restoring to an empty data tree (i.e. no backup files) is valid for restoring to
    // latest
    if (zxidToRestore == Long.MAX_VALUE) {
      return true;
    }

    return false;
  }

  /**
   * Find the next snapshot whose range is below the requested restore point.
   * Note: in practice this only gets called once when zxidToRestore != Long.MAX
   * @return true if a snapshot is found; false in all other cases.
   */
  private boolean findNextPossibleSnapshot() {
    for (snapNeededIndex++; snapNeededIndex < snaps.size(); snapNeededIndex++) {
      if (snaps.get(snapNeededIndex).getZxidRange().upperEndpoint() <= zxidToRestore) {
        return true;
      }
    }

    return false;
  }

  /**
   * Find the range of log files needed to make the current proposed snapshot transactionally
   * consistent to the requested zxid.
   * When zxidToRestore == Long.MAX the transaction log range terminates either on the most
   * recent backedup txnlog OR the last txnlog prior to a lost log range (assuming that log
   * still makes the snapshot transactionally consistent).
   * @return true if a valid log range is found; false in all other cases.
   */
  private boolean findLogRange() {
    Preconditions.checkState(snapNeededIndex >= 0 && snapNeededIndex < snaps.size());

    if (logs.size() == 0) {
      return false;
    }

    BackupFileInfo snap = snaps.get(snapNeededIndex);
    Range<Long> snapRange = snap.getZxidRange();
    oldestLogNeededIndex = logs.size() - 1;
    mostRecentLogNeededIndex = 0;

    // Find the first txlog that might make the snapshot valid, OR is a lost log
    for (int i = 0; i < logs.size() - 1; i++) {
      BackupFileInfo log = logs.get(i);
      Range<Long> logRange = log.getZxidRange();

      if (logRange.lowerEndpoint() <= snapRange.lowerEndpoint()) {
        oldestLogNeededIndex = i;
        break;
      }
    }

    // Starting if the oldest log that might allow the snapshot to be valid, find the txnlog
    // that includes the restore point, OR is a lost log
    for (int i = oldestLogNeededIndex; i > 0; i--) {
      BackupFileInfo log = logs.get(i);
      Range<Long> logRange = log.getZxidRange();

      if (log.getFileType() == BackupFileType.LOSTLOG
          || logRange.upperEndpoint() >= zxidToRestore) {

        mostRecentLogNeededIndex = i;
        break;
      }
    }

    return validateLogRange();
  }

  private boolean validateLogRange() {
    Preconditions.checkState(oldestLogNeededIndex >= 0);
    Preconditions.checkState(oldestLogNeededIndex < logs.size());
    Preconditions.checkState(mostRecentLogNeededIndex >= 0);
    Preconditions.checkState(mostRecentLogNeededIndex < logs.size());
    Preconditions.checkState(oldestLogNeededIndex >= mostRecentLogNeededIndex);
    Preconditions.checkState(snapNeededIndex >= 0);
    Preconditions.checkState(snapNeededIndex < snaps.size());

    BackupFileInfo snap = snaps.get(snapNeededIndex);
    BackupFileInfo oldestLog = logs.get(oldestLogNeededIndex);
    BackupFileInfo newestLog = logs.get(mostRecentLogNeededIndex);

    if (oldestLog.getFileType() == BackupFileType.LOSTLOG) {
      LOG.error("Could not find logs to make the snapshot '" + snap.getBackedUpFile()
          + "' valid. Lost logs at " + logs.get(oldestLogNeededIndex).getZxidRange());
      return false;
    }

    if (newestLog.getFileType() == BackupFileType.LOSTLOG) {
      if (zxidToRestore == Long.MAX_VALUE && oldestLogNeededIndex != mostRecentLogNeededIndex) {
        // When restoring to the latest, we can use the last valid log prior to lost log
        // range.
        mostRecentLogNeededIndex++;
      } else {
        LOG.error("Could not find logs to make the snapshot '" + snap.getBackedUpFile()
            + "' valid. Lost logs at " + logs.get(mostRecentLogNeededIndex).getZxidRange() + ".");
        return false;
      }
    }

    Range<Long> fullRange = oldestLog.getZxidRange().span(newestLog.getZxidRange());

    if (fullRange.lowerEndpoint() > snap.getZxidRange().lowerEndpoint()) {
      LOG.error("Could not find logs to make snap '" + snap.getBackedUpFile()
          + "' valid. Logs start at zxid " + ZxidUtils.zxidToString(fullRange.lowerEndpoint())
          + ".");
      return false;
    }

    if (fullRange.upperEndpoint() < snap.getZxidRange().upperEndpoint()) {
      LOG.error("Could not find logs to make snap '" + snap.getBackedUpFile()
          + "' valid. Logs end at zxid " + ZxidUtils.zxidToString(fullRange.upperEndpoint()) + ".");
      return false;
    }

    if (zxidToRestore != Long.MAX_VALUE && fullRange.upperEndpoint() < zxidToRestore) {
      LOG.error("Could not find logs to restore to zxid " + zxidToRestore + ". Logs end at zxid "
          + ZxidUtils.zxidToString(fullRange.upperEndpoint()) + ".");
      return false;
    }

    return true;
  }

  /**
   * Copy selected backup files from backup storage to a local restore temporary directory for further processing later
   * @param restoreTempSnapLog A FileTxnSnapLog instance created on the specified local temporary directory path
   * @throws IOException
   */
  private void copyBackupFilesToLocalTempDir(FileTxnSnapLog restoreTempSnapLog) throws IOException {
    // Copy backup files to local temp directory
    for (BackupFileInfo backedupFile : filesToCopy) {
      String standardFilename = backedupFile.getStandardFile().getName();
      // Does not matter if we use dataDir or logDir since we make the paths of these two directories
      // same in restoreTempSnapLog object for restoration
      File localTempDest = new File(restoreTempSnapLog.getDataDir(), standardFilename);

      if (!localTempDest.exists()) {
        LOG.info("Copying " + backedupFile.getBackedUpFile() + " from backup storage to temp dir "
            + localTempDest.getPath() + ".");
        storage.copyToLocalStorage(backedupFile.getBackedUpFile(), localTempDest);
      } else {
        throw new BackupException("Cannot copy " + backedupFile.getBackedUpFile()
            + " to the local temp directory because it already exists as " + localTempDest.getPath()
            + ".");
      }
    }
  }

  /**
   * Process the copied backup files stored in local restore temporary directory to get them ready to be copied to final destination
   * The processing currently includes truncating txn logs to zxidToRestore.
   * @param restoreTempSnapLog A FileTxnSnapLog instance created on the specified local temporary directory path
   * @param zxidToRestore The zxid to restore to
   * @throws IOException
   */
  private void processCopiedBackupFiles(FileTxnSnapLog restoreTempSnapLog, long zxidToRestore) {
    if (zxidToRestore != Long.MAX_VALUE) {
      restoreTempSnapLog.truncateLog(zxidToRestore);
      LOG.info(
          "Successfully truncate the logs inside restoreTempDir " + restoreTempDir + " to zxid "
              + zxidToRestore);
    }
  }

  /**
   * Copy the processed files from local restore temp directory to final destination:
   * snapshot to snapDir, txn logs to logDir.
   * @param restoreTempSnapLog A FileTxnSnapLog instance created on the specified local temporary directory path
   * @throws IOException
   */
  private void copyProcessedRestoredFilesToDestination(FileTxnSnapLog restoreTempSnapLog)
      throws IOException {
    // Does not matter if we use dataDir or logDir since we make the paths of these two directories
    // same in restoreTempSnapLog object for restoration
    File[] processedFiles = restoreTempSnapLog.getDataDir().listFiles();
    if (processedFiles == null) {
      throw new BackupException(
          "Failed to get a list of processed files from the local temp directory.");
    }
    for (File processedFile : processedFiles) {
      String fileName = processedFile.getName();
      if (!Util.isSnapshotFileName(fileName) && !Util.isLogFileName(fileName)) {
        // Skip files that aren't snapshot nor transaction logs. Should only happen in tests.
        LOG.info("Skipping copying " + processedFile.getPath()
            + " from temp dir to final destination directory.");
        continue;
      }
      File finalDestinationBase =
          Util.isSnapshotFileName(fileName) ? snapLog.getSnapDir() : snapLog.getDataDir();
      LOG.info(
          "Copying " + processedFile.getPath() + " from temp dir to final destination directory "
              + finalDestinationBase.getPath() + ".");
      Files.copy(processedFile.toPath(), new File(finalDestinationBase, fileName).toPath());
    }
  }
}
