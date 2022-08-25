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

import static com.google.common.base.Preconditions.checkState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Range;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.cli.CommandLine;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.cli.RestoreCommand;
import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.IntervalEndpoint;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.storage.BackupStorageUtil;
import org.apache.zookeeper.server.backup.timetable.TimetableBackup;
import org.apache.zookeeper.server.backup.timetable.TimetableUtil;
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

  private static final int MAX_RETRIES = 10;
  private static final String HEX_PREFIX = "0x";
  private static final int CONNECTION_TIMEOUT = 300000;

  BackupStorageProvider storage;
  FileTxnSnapLog snapLog;
  long zxidToRestore;
  boolean dryRun;
  File restoreTempDir;
  boolean overwrite = false;

  // Spot restoration
  boolean isSpotRestoration = false;
  String znodePathToRestore;
  String zkServerConnectionStr;
  boolean restoreRecursively = false;
  ZooKeeper zk;
  SpotRestorationTool spotRestorationTool;

  List<BackupFileInfo> logs;
  List<BackupFileInfo> snaps;
  List<BackupFileInfo> filesToCopy;

  int mostRecentLogNeededIndex;
  int snapNeededIndex;
  int oldestLogNeededIndex;

  public enum BackupStorageOption {
    GPFS("org.apache.zookeeper.server.backup.storage.impl.FileSystemBackupStorage"),
    NFS("org.apache.zookeeper.server.backup.storage.impl.FileSystemBackupStorage");

    private String storageProviderClassName;

    BackupStorageOption(String className) {
      this.storageProviderClassName = className;
    }

    public String getStorageProviderClassName() {
      return storageProviderClassName;
    }
  }

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
   * Parse and validate arguments to the tool
   * @param cl the command line object with user inputs
   * @return true if the arguments parse correctly; false in all other cases.
   * @throws IOException if the backup provider cannot be instantiated correctly.
   */
  public void parseArgs(CommandLine cl) throws Exception {
    String backupStoragePath = cl.getOptionValue(RestoreCommand.OptionShortForm.BACKUP_STORE);
    createBackupStorageProvider(backupStoragePath);

    // Read the restore point
    if (cl.hasOption(RestoreCommand.OptionShortForm.RESTORE_ZXID)) {
      parseRestoreZxid(cl);
    } else if (cl.hasOption(RestoreCommand.OptionShortForm.RESTORE_TIMESTAMP)) {
      parseRestoreTimestamp(cl, backupStoragePath);
    }

    parseAndValidateSpotRestorationArgs(cl);

    parseAndValidateOfflineRestoreDestination(cl);

    parseRestoreTempDir(cl);

    // Check if overwriting the destination directories is allowed
    if (cl.hasOption(RestoreCommand.OptionShortForm.OVERWRITE)) {
      overwrite = true;
    }

    // Check if this is a dry-run
    if (cl.hasOption(RestoreCommand.OptionShortForm.DRY_RUN)) {
      dryRun = true;
    }

    System.out.println("parseArgs successful.");
  }

  private void createBackupStorageProvider(String backupStoragePath) throws Exception {
    String[] backupStorageParams = backupStoragePath.split(":");
    if (backupStorageParams.length != 4) {
      System.err.println(
          "Failed to parse backup storage connection information from the backup storage path provided, please check the input.");
      System.err.println(
          "For example: the format for a gpfs backup storage path should be \"gpfs:<config_path>:<backup_path>:<namespace>\".");
      throw new IllegalArgumentException("Failed to create backup storage provider");
    }

    String userProvidedStorageName = backupStorageParams[0].toUpperCase();
    try {
      BackupStorageOption storageOption = BackupStorageOption.valueOf(userProvidedStorageName);
      String backupStorageProviderClassName = storageOption.getStorageProviderClassName();

      BackupConfig.RestorationConfigBuilder configBuilder =
          new BackupConfig.RestorationConfigBuilder()
              .setStorageProviderClassName(backupStorageProviderClassName)
              .setBackupStoragePath(backupStorageParams[2]).setNamespace(backupStorageParams[3]);
      if (!backupStorageParams[1].isEmpty()) {
        configBuilder = configBuilder.setStorageConfig(new File(backupStorageParams[1]));
      }
      storage = BackupUtil.createStorageProviderImpl(configBuilder.build().get());
    } catch (IllegalArgumentException e) {
      System.err.println("Could not find a valid backup storage option based on the input: "
          + userProvidedStorageName + ". Error message: " + e.getMessage());
      e.printStackTrace();
      throw e;
    } catch (ConfigException e) {
      System.err.println(
          "Could not generate a backup config based on the input, error message: " + e
              .getMessage());
      e.printStackTrace();
      throw e;
    } catch (InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
      System.err.println(
          "Could not generate a backup storage provider based on the input, error message: " + e
              .getMessage());
      e.printStackTrace();
      throw e;
    }
  }

  private void parseRestoreZxid(CommandLine cl) {
    String zxidToRestoreStr = cl.getOptionValue(RestoreCommand.OptionShortForm.RESTORE_ZXID);
    if (zxidToRestoreStr.equalsIgnoreCase(BackupUtil.LATEST)) {
      zxidToRestore = Long.MAX_VALUE;
    } else {
      int base = 10;
      String numStr = zxidToRestoreStr;

      if (zxidToRestoreStr.startsWith(HEX_PREFIX)) {
        numStr = zxidToRestoreStr.substring(2);
        base = 16;
      }
      try {
        zxidToRestore = Long.parseLong(numStr, base);
      } catch (NumberFormatException nfe) {
        System.err
            .println("Invalid number specified for restore zxid point, the input is: " + numStr);
        throw nfe;
      }
    }
  }

  private void parseRestoreTimestamp(CommandLine cl, String backupStoragePath) {
    String timestampStr = cl.getOptionValue(RestoreCommand.OptionShortForm.RESTORE_TIMESTAMP);
    String timetableStoragePath = backupStoragePath;
    if (cl.hasOption(RestoreCommand.OptionShortForm.TIMETABLE_STORAGE_PATH)) {
      timetableStoragePath =
          cl.getOptionValue(RestoreCommand.OptionShortForm.TIMETABLE_STORAGE_PATH);
    }
    File[] timetableFiles = new File(timetableStoragePath)
        .listFiles(file -> file.getName().startsWith(TimetableBackup.TIMETABLE_PREFIX));
    if (timetableFiles == null || timetableFiles.length == 0) {
      System.err.println("Could not find timetable files at the path: " + timetableStoragePath);
      throw new IllegalArgumentException("Could not find timetable files.");
    }
    Map.Entry<Long, String> restorePoint;
    String message;
    try {
      restorePoint = TimetableUtil.findLastZxidFromTimestamp(timetableFiles, timestampStr);
      zxidToRestore = Long.parseLong(restorePoint.getValue(), 16);
      String timeToRestore = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
          .format(new java.util.Date(restorePoint.getKey()));
      if (timestampStr.equalsIgnoreCase(BackupUtil.LATEST)) {
        message = "Restoring to " + timeToRestore + ", original request was to restore to latest.";
      } else {
        String requestedTimeToRestore = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
            .format(new java.util.Date(Long.decode(timestampStr)));
        message = "Restoring to " + timeToRestore + ", original request was to restore to "
            + requestedTimeToRestore + ".";
      }
      System.out.println(message);
      LOG.info(message);
    } catch (IllegalArgumentException | BackupException e) {
      System.err.println(
          "Could not find a valid zxid from timetable using the timestamp provided: " + timestampStr
              + ". The error message is: " + e.getMessage());
      e.printStackTrace();
      throw e;
    }
  }

  private void parseAndValidateOfflineRestoreDestination(CommandLine cl) throws IOException {
    if (isSpotRestoration) {
      return;
    }
    // Read restore destination: dataDir and logDir
    try {
      String snapDirPath = cl.getOptionValue(RestoreCommand.OptionShortForm.SNAP_DESTINATION);
      String logDirPath = cl.getOptionValue(RestoreCommand.OptionShortForm.LOG_DESTINATION);

      if (snapDirPath == null || logDirPath == null) {
        throw new BackupException(
            "Snap destination path and log destination path are not defined for offline restoration. SnapDirPath: "
                + snapDirPath + ", logDirPath: " + logDirPath);
      }

      File snapDir = new File(snapDirPath);
      File logDir = new File(logDirPath);
      snapLog = new FileTxnSnapLog(logDir, snapDir);
      checkSnapDataDirFileExistence();
    } catch (IOException ioe) {
      System.err.println("Could not setup transaction log utility." + ioe);
      throw ioe;
    }
  }

  private void parseRestoreTempDir(CommandLine cl) {
    if (cl.hasOption(RestoreCommand.OptionShortForm.LOCAL_RESTORE_TEMP_DIR_PATH)) {
      String localRestoreTempDirPath =
          cl.getOptionValue(RestoreCommand.OptionShortForm.LOCAL_RESTORE_TEMP_DIR_PATH);
      restoreTempDir = new File(localRestoreTempDirPath);
    }

    if (restoreTempDir == null) {
      if (isSpotRestoration) {
        throw new BackupException(
            "Local restore temp dir path is not defined for spot restoration.");
      } else {
        // This is an offline restoration
        // If the user hasn't provided the restore temp dir parameter,
        //then the tool will just create a temporary folder inside snapLog and delete it afterwards.
        this.restoreTempDir = new File(snapLog.getDataDir(), "RestoreTempDir_" + zxidToRestore);
      }
    }
  }

  private void parseAndValidateSpotRestorationArgs(CommandLine cl) {
    if (cl.hasOption(RestoreCommand.OptionShortForm.ZNODE_PATH_TO_RESTORE)) {
      znodePathToRestore = cl.getOptionValue(RestoreCommand.OptionShortForm.ZNODE_PATH_TO_RESTORE);
    }
    if (cl.hasOption(RestoreCommand.OptionShortForm.ZK_SERVER_CONNECTION_STRING)) {
      zkServerConnectionStr =
          cl.getOptionValue(RestoreCommand.OptionShortForm.ZK_SERVER_CONNECTION_STRING);
    }
    if (cl.hasOption(RestoreCommand.OptionShortForm.RECURSIVE_SPOT_RESTORE)) {
      restoreRecursively = true;
    }
    if (znodePathToRestore != null && zkServerConnectionStr != null) {
      isSpotRestoration = true;
    } else if (znodePathToRestore == null && zkServerConnectionStr == null) {
      isSpotRestoration = false;
    } else {
      throw new BackupException(
          "Znode path and zk server connection string must be provided in order to do spot restoration. Provided znode path: "
              + znodePathToRestore + ", provided zk server connection string: "
              + zkServerConnectionStr);
    }
  }

  /**
   * Attempts to perform a restore with up to MAX_RETRIES retries.
   * @return true if the restore completed successfully, false in all other cases.
   */
  public boolean runWithRetries(CommandLine cl) {
    try {
      parseArgs(cl);
    } catch (Exception e) {
      System.err.println(
          "Restore attempt failed, could not parse arguments. "
              + "Error message: " + e.getMessage());
      return false;
    }

    int tries = 0;

    if (dryRun) {
      System.out.println("This is a DRYRUN, no files will actually be copied.");
    }

    while (tries < MAX_RETRIES) {
      try {
        run();
        return true;
      } catch (IllegalArgumentException re) {
        System.err.println(
            "Restore attempt failed, could not find all the required backup files to restore. "
                + "Error message: " + re.getMessage());
        re.printStackTrace();
        return false;
      } catch (BackupException be) {
        System.err.println(
            "Restoration attempt failed due to a backup exception, it's usually caused by required "
                + "directories not existing or failure of creating directories, etc. Please check the message. "
                + "Error message: " + be.getMessage());
        be.printStackTrace();
        return false;
      } catch (Exception e) {
        tries++;
        System.err.println("Restore attempt failed; attempting again. " + tries + "/" + MAX_RETRIES
            + ". Error message: " + e.getMessage());
        e.printStackTrace();
      }
    }

    System.err.println("Failed to restore after " + (tries + 1) + " attempts.");
    return false;
  }

  /**
   * Attempts to perform a restore.
   */
  public void run() throws IOException, InterruptedException {
    try {
      if (!findFilesToRestore()) {
        throw new IllegalArgumentException("Failed to find valid snapshot and logs to restore.");
      }

      if (restoreTempDir == null || storage == null) {
        throw new BackupException("The RestoreTempDir and BackupStorageProvider cannot be null.");
      }

      if (!restoreTempDir.exists() && !restoreTempDir.mkdirs()) {
        throw new BackupException(
            "Failed to create a temporary directory at path: " + restoreTempDir.getPath()
                + " to store copied backup files.");
      }

      if (!dryRun) {
        // This step will create a "version-2" directory inside restoreTempDir,
        // all the selected backup files will be copied to version-2 directory
        FileTxnSnapLog restoreTempSnapLog =
            new FileTxnSnapLog(this.restoreTempDir, this.restoreTempDir);

        copyBackupFilesToLocalTempDir(restoreTempSnapLog);
        processCopiedBackupFiles(restoreTempSnapLog, zxidToRestore);
        if (isSpotRestoration) {
          performSpotRestoration(restoreTempDir);
        } else {
          // It is an offline restoration
          copyProcessedRestoredFilesToDestination(restoreTempSnapLog);
        }
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
    snaps = BackupUtil.getBackupFiles(storage, BackupFileType.SNAPSHOT, IntervalEndpoint.START,
        BackupUtil.SortOrder.DESCENDING);
    logs = BackupUtil.getBackupFiles(storage,
        new BackupFileType[]{BackupFileType.TXNLOG, BackupFileType.LOSTLOG}, IntervalEndpoint.START,
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
      if (snaps.get(snapNeededIndex).getRange().upperEndpoint() <= zxidToRestore) {
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
    checkState(snapNeededIndex >= 0 && snapNeededIndex < snaps.size());

    if (logs.size() == 0) {
      return false;
    }

    BackupFileInfo snap = snaps.get(snapNeededIndex);
    Range<Long> snapRange = snap.getRange();
    oldestLogNeededIndex = logs.size() - 1;
    mostRecentLogNeededIndex = 0;

    // Find the first txlog that might make the snapshot valid, OR is a lost log
    for (int i = 0; i < logs.size() - 1; i++) {
      BackupFileInfo log = logs.get(i);
      Range<Long> logRange = log.getRange();

      if (logRange.lowerEndpoint() <= snapRange.lowerEndpoint()) {
        oldestLogNeededIndex = i;
        break;
      }
    }

    // Starting if the oldest log that might allow the snapshot to be valid, find the txnlog
    // that includes the restore point, OR is a lost log
    for (int i = oldestLogNeededIndex; i > 0; i--) {
      BackupFileInfo log = logs.get(i);
      Range<Long> logRange = log.getRange();

      if (log.getFileType() == BackupFileType.LOSTLOG
          || logRange.upperEndpoint() >= zxidToRestore) {

        mostRecentLogNeededIndex = i;
        break;
      }
    }

    return validateLogRange();
  }

  private boolean validateLogRange() {
    checkState(oldestLogNeededIndex >= 0);
    checkState(oldestLogNeededIndex < logs.size());
    checkState(mostRecentLogNeededIndex >= 0);
    checkState(mostRecentLogNeededIndex < logs.size());
    checkState(oldestLogNeededIndex >= mostRecentLogNeededIndex);
    checkState(snapNeededIndex >= 0);
    checkState(snapNeededIndex < snaps.size());

    BackupFileInfo snap = snaps.get(snapNeededIndex);
    BackupFileInfo oldestLog = logs.get(oldestLogNeededIndex);
    BackupFileInfo newestLog = logs.get(mostRecentLogNeededIndex);

    if (oldestLog.getFileType() == BackupFileType.LOSTLOG) {
      LOG.error("Could not find logs to make the snapshot '" + snap.getBackedUpFile()
          + "' valid. Lost logs at " + logs.get(oldestLogNeededIndex).getRange());
      return false;
    }

    if (newestLog.getFileType() == BackupFileType.LOSTLOG) {
      if (zxidToRestore == Long.MAX_VALUE && oldestLogNeededIndex != mostRecentLogNeededIndex) {
        // When restoring to the latest, we can use the last valid log prior to lost log
        // range.
        mostRecentLogNeededIndex++;
      } else {
        LOG.error("Could not find logs to make the snapshot '" + snap.getBackedUpFile()
            + "' valid. Lost logs at " + logs.get(mostRecentLogNeededIndex).getRange() + ".");
        return false;
      }
    }

    Range<Long> fullRange = oldestLog.getRange().span(newestLog.getRange());

    if (fullRange.lowerEndpoint() > snap.getRange().lowerEndpoint()) {
      LOG.error("Could not find logs to make snap '" + snap.getBackedUpFile()
          + "' valid. Logs start at zxid " + ZxidUtils.zxidToString(fullRange.lowerEndpoint())
          + ".");
      return false;
    }

    if (fullRange.upperEndpoint() < snap.getRange().upperEndpoint()) {
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
   * Check if the specified snap dir and data dir already have files inside.
   * If so, ask user to confirm if they want to overwrite these two directories with restored files,
   * which means to wipe out all existing files and the directories be populated with restored files.
   * Note : Supressing no null spotbugs warning because it's showing false positive thing and might be fixed in
   * https://github.com/spotbugs/spotbugs/issues/456
   */
  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void checkSnapDataDirFileExistence() {
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
    String[] dataDirFiles = dataDir.list();
    String[] snapDirFiles = snapDir.list();
    if (Objects.requireNonNull(dataDirFiles).length > 0
        || Objects.requireNonNull(snapDirFiles).length > 0) {
      if (overwrite) {
        LOG.warn(
            "Overwriting the destination directories for restoration, deleting all existing files. "
                + "The files under dataDir: " + dataDir.getPath() + " are: " + Arrays
                .toString(dataDirFiles) + "; and files under snapDir: " + snapDir.getPath()
                + " are: " + Arrays.toString(snapDirFiles) + ".");
        Arrays.stream(Objects.requireNonNull(dataDir.listFiles())).forEach(File::delete);
        Arrays.stream(Objects.requireNonNull(snapDir.listFiles())).forEach(File::delete);
      } else {
        throw new BackupException(
            "The destination directories are not empty, user chose not to overwrite the entire directory, "
                + "exiting restoration. Please check the destination directory dataDir path: "
                + dataDir.getPath() + ", and snapDir path" + snapDir.getPath());
      }
    }
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
    LOG.info(
        "All files were successfully copied to destination directory. Offline restoration was completed.");
  }

  /**
   * If the CLI command has specified a znode path to perform spot restoration,
   * run the spot restoration tool on that path
   * @throws IOException
   */
  @VisibleForTesting
  protected void performSpotRestoration(File restoreTempDir)
      throws IOException, InterruptedException {
    LOG.info("Starting spot restoration for zk path " + znodePathToRestore);
    zk = new ZooKeeper(zkServerConnectionStr, CONNECTION_TIMEOUT, (event) -> {
      LOG.info("WATCHER:: client-server connection event received for spot restoration: " + event
          .toString());
    });
    spotRestorationTool =
        new SpotRestorationTool(restoreTempDir, zk, znodePathToRestore, restoreRecursively);
    spotRestorationTool.run();
  }
}
