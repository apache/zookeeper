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

package org.apache.zookeeper.server.backup.timetable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.zookeeper.server.backup.BackupFileInfo;
import org.apache.zookeeper.server.backup.BackupManager;
import org.apache.zookeeper.server.backup.BackupPoint;
import org.apache.zookeeper.server.backup.BackupProcess;
import org.apache.zookeeper.server.backup.BackupStatus;
import org.apache.zookeeper.server.backup.BackupUtil;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.monitoring.TimetableBackupStats;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.LoggerFactory;

/**
 * Implements Timetable-specific logic for BackupProcess.
 *
 * Backup timetable encodes data of the format <timestamp>:<zxid>. This is used to locate the
 * closest zxid backup point given the timestamp. The use case is for users who wish to restore
 * from backup at a specific time recorded in the backup timetable.
 */
public class TimetableBackup extends BackupProcess {
  public static final String TIMETABLE_PREFIX = "timetable";
  // Use an ordered map of <timestamp>:<zxid>
  private final TreeMap<Long, String> timetableRecordMap = new TreeMap<>();
  private final File tmpDir;
  // Lock is used to keep access to timetableRecordMap exclusive
  private final Lock lock = new ReentrantLock(true);

  // Candidate files to be backed up each iteration sorted by name (using SortedSet)
  private final SortedSet<BackupManager.BackupFile> candidateTimetableBackupFiles =
      new TreeSet<>(Comparator.comparing(o -> o.getFile().getName()));
  // BackupStatus is used here to keep track of timetable backup status in case of a crash/restart
  // BackupStatus is written to a file. After a crash (e.g. JVM crash) or a restart, the
  // locally-stored zkBackupStatus file will be read back to restore a BackupPoint
  private final BackupStatus backupStatus;
  private final BackupPoint backupPoint;
  private final TimetableBackupStats backupStats; // Metrics

  /**
   * Create an instance of TimetableBackup.
   * @param snapLog
   * @param tmpDir
   * @param backupStorageProvider
   * @param backupIntervalInMilliseconds
   * @param timetableBackupIntervalInMs
   * @param backupStatus
   */
  public TimetableBackup(FileTxnSnapLog snapLog, File tmpDir,
      BackupStorageProvider backupStorageProvider, long backupIntervalInMilliseconds,
      long timetableBackupIntervalInMs, BackupStatus backupStatus, BackupPoint backupPoint,
      TimetableBackupStats backupStats) {
    super(LoggerFactory.getLogger(TimetableBackup.class), backupStorageProvider,
        backupIntervalInMilliseconds);
    this.tmpDir = tmpDir;
    this.backupStatus = backupStatus;
    this.backupPoint = backupPoint;
    this.backupStats = backupStats;
    // Start creating records
    (new Thread(new TimetableRecorder(snapLog, timetableBackupIntervalInMs))).start();
    logger.info("TimetableBackup::Starting TimetableBackup Process with backup interval: "
        + backupIntervalInMilliseconds + " ms and timetable backup interval: "
        + timetableBackupIntervalInMs + " ms.");
  }

  @Override
  protected void initialize() throws IOException {
    // Get the latest timetable backup file from backup storage
    BackupFileInfo latest = BackupUtil.getLatest(backupStorage, BackupUtil.BackupFileType.TIMETABLE,
        BackupUtil.IntervalEndpoint.END);

    long latestTimestampBackedUp = latest == null ? BackupUtil.INVALID_TIMESTAMP
        : latest.getIntervalEndpoint(BackupUtil.IntervalEndpoint.END);

    logger.info(
        "TimetableBackup::initialize(): latest timestamp from storage: {}, from BackupStatus: {}",
        latestTimestampBackedUp, backupPoint.getTimestamp());

    if (latestTimestampBackedUp != backupPoint.getTimestamp()) {
      synchronized (backupStatus) {
        backupPoint.setTimestamp(latestTimestampBackedUp);
        backupStatus.update(backupPoint);
      }
    }
  }

  @Override
  protected void startIteration() throws IOException {
    backupStats.setTimetableBackupIterationStart();

    // It's possible that the last iteration failed and left some tmp backup files behind - add
    // them back to candidate backup files so the I/O write will happen again
    getAllTmpBackupFiles();

    // Create a timetable backup file from the cached records (timetableRecordMap)
    lock.lock();
    try {
      if (!timetableRecordMap.isEmpty()) {
        // Create a temp timetable backup file
        // Make the backup file temporary so that it will be deleted after the iteration provided
        // the I/O write to the backup storage succeeds. Otherwise, this temporary backup file will
        // continue to exist in tmpDir locally until it's successfully written to the backup storage
        BackupManager.BackupFile timetableBackupFromThisIteration =
            new BackupManager.BackupFile(createTimetableBackupFile(), true, 0, 0);
        candidateTimetableBackupFiles.add(timetableBackupFromThisIteration);
        timetableRecordMap.clear(); // Clear the map
      }
    } catch (Exception e) {
      logger.error("TimetableBackup::startIteration(): failed to create timetable file to back up!",
          e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void endIteration(boolean errorFree) {
    // timetableRecordMap is cleared in startIteration() already, so we do not clear it here
    backupStats.setTimetableBackupIterationDone(errorFree);
  }

  @Override
  protected BackupManager.BackupFile getNextFileToBackup() throws IOException {
    BackupManager.BackupFile nextFile = null;
    if (!candidateTimetableBackupFiles.isEmpty()) {
      nextFile = candidateTimetableBackupFiles.first();
    }
    return nextFile;
  }

  @Override
  protected void backupComplete(BackupManager.BackupFile file) throws IOException {
    // Remove from the candidate set because it has been copied to the backup storage successfully
    candidateTimetableBackupFiles.remove(file);
    synchronized (backupStatus) {
      // Update the latest timestamp to which the timetable backup was successful
      backupPoint.setTimestamp(Long.parseLong(file.getFile().getName().split("-")[1]));
      backupStatus.update(backupPoint);
    }
    logger.info(
        "TimetableBackup::backupComplete(): backup complete for file: {}. Updated backed up "
            + "timestamp to {}", file.getFile().getName(), backupPoint.getTimestamp());
  }

  /**
   * Create a file name for timetable backup.
   * E.g.) timetable.<lowest timestamp>-<highest timestamp>
   * @return
   */
  private String makeTimetableBackupFileName() {
    return String.format("%s.%d-%d", TIMETABLE_PREFIX, timetableRecordMap.firstKey(),
        timetableRecordMap.lastKey());
  }

  /**
   * Write the content of timetableRecordMap to a file using a tmpDir.
   * @return
   * @throws IOException
   */
  private File createTimetableBackupFile() throws IOException {
    File tempTimetableBackupFile = new File(tmpDir, makeTimetableBackupFileName());
    logger.info(
        "TimetableBackup::createTimetableBackupFile(): created temporary timetable backup file with"
            + " name: " + tempTimetableBackupFile.getName());
    FileOutputStream fos = new FileOutputStream(tempTimetableBackupFile);
    ObjectOutputStream oos = new ObjectOutputStream(fos);
    oos.writeObject(timetableRecordMap);
    oos.flush();
    oos.close();
    fos.close();
    logger.info(
        "TimetableBackup::createTimetableBackupFile(): successfully wrote cached timetable data to "
            + "temporary timetable backup file with name: " + tempTimetableBackupFile.getName());
    return tempTimetableBackupFile;
  }

  /**
   * Get all temporary timetable backup files in tmpDir.
   */
  private void getAllTmpBackupFiles() {
    File[] tmpBackupFiles = tmpDir.listFiles(f -> f.getName().startsWith(TIMETABLE_PREFIX));
    if (tmpBackupFiles != null && tmpBackupFiles.length != 0) {
      for (File file : tmpBackupFiles) {
        // Note that zxid are not needed for timetable backup files, so we just put 0 here
        candidateTimetableBackupFiles.add(new BackupManager.BackupFile(file, true, 0, 0));
      }
    }
  }

  /**
   * Child thread that records timetable records (<timestamp>:<zxid>) at the given interval.
   *
   * TimetableBackup: parent thread responsible for creating timetable backup files from
   * cached record (map) and writing to backup storage
   * TimetableRecorder: child thread responsible for adding timetable-zxid mappings to the cached
   * map at every timetable backup interval.
   */
  class TimetableRecorder implements Runnable {
    private final FileTxnSnapLog snapLog;
    private final long timetableBackupIntervalInMs;
    private long lastLoggedZxidFromLastRun;

    public TimetableRecorder(FileTxnSnapLog snapLog, long timetableBackupIntervalInMs) {
      this.snapLog = snapLog;
      this.timetableBackupIntervalInMs = timetableBackupIntervalInMs;
      logger.info("TimetableRecorder created with timetable backup interval at "
          + timetableBackupIntervalInMs + " ms.");
    }

    @Override
    public void run() {
      // The lifecycle of this child thread should be tied to that of TimetableBackup
      while (isRunning) {
        try {
          lock.lock();
          try {
            TxnHeader lastLoggedTxnHeader = snapLog.getLastLoggedTxnHeader();
            if (lastLoggedTxnHeader != null) {
              // Ignore if the last logged zxid is not valid (-1) because -1 (int) will cause a
              // NumberFormatException during conversion to Hex String
              // Also do not create repeat records
              long zxid = lastLoggedTxnHeader.getZxid();
              long timestamp = lastLoggedTxnHeader.getTime();
              if (zxid >= 0 && zxid != lastLoggedZxidFromLastRun) {
                timetableRecordMap.put(timestamp, Long.toHexString(zxid));
                lastLoggedZxidFromLastRun = zxid;
              }
            }
          } catch (Exception e) {
            // Bad state, this shouldn't happen. Throw an exception
            throw new BackupException(
                "TimetableRecorder::run(): failed to record a timestamp-zxid mapping!", e);
          } finally {
            lock.unlock();
          }
          synchronized (TimetableBackup.this) {
            // synchronized to get notification of termination in case of shutdown
            // must synchronize on the outer class's reference (TimetableBackup.this) because
            // the notifyAll() call wakes up all BackupProcess instances
            TimetableBackup.this.wait(timetableBackupIntervalInMs);
          }
        } catch (InterruptedException e) {
          logger.warn("TimetableRecorder::run(): Interrupted exception while waiting for "
              + "timetable backup interval.", e.fillInStackTrace());
        } catch (Exception e) {
          logger.error("TimetableRecorder::run(): Hit unexpected exception", e.fillInStackTrace());
        }
      }
    }
  }
}
