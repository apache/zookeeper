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
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.zookeeper.server.backup.BackupManager;
import org.apache.zookeeper.server.backup.BackupProcess;
import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.TxnLog;
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

  // File to be backed up each iteration
  private BackupManager.BackupFile timetableBackupFile;

  /**
   * Create an instance of TimetableBackup.
   * @param snapLog
   * @param tmpDir
   * @param backupStorageProvider
   * @param backupIntervalInMilliseconds
   * @param timetableBackupIntervalInMs
   */
  public TimetableBackup(FileTxnSnapLog snapLog, File tmpDir,
      BackupStorageProvider backupStorageProvider, long backupIntervalInMilliseconds,
      long timetableBackupIntervalInMs) {
    super(LoggerFactory.getLogger(TimetableBackup.class), backupStorageProvider,
        backupIntervalInMilliseconds);
    this.tmpDir = tmpDir;
    // Start creating records
    (new Thread(new TimetableRecorder(snapLog, timetableBackupIntervalInMs))).start();
    logger.info("TimetableBackup::Starting TimetableBackup Process with backup interval: "
        + backupIntervalInMilliseconds + " ms and timetable backup interval: "
        + timetableBackupIntervalInMs + " ms.");
  }

  @Override
  protected void initialize() throws IOException {
  }

  @Override
  protected void startIteration() throws IOException {
    // Create a timetable backup file from the cached records (timetableRecordMap)
    lock.lock();
    try {
      if (!timetableRecordMap.isEmpty()) {
        // Create a temp timetable backup file
        // Putting the zxid values here is okay; the actual backup file name will contain the
        // starting and ending timestamps, not zxids
        long lowestZxid = Long.valueOf(timetableRecordMap.firstEntry().getValue(), 16);
        long highestZxid = Long.valueOf(timetableRecordMap.lastEntry().getValue(), 16);
        timetableBackupFile =
            new BackupManager.BackupFile(createTimetableBackupFile(), true, lowestZxid,
                highestZxid); // make it temporary so that it will be deleted after iteration
        timetableRecordMap.clear(); // Clear the map
      }
    } catch (Exception e) {
      logger.error(
          "TimetableBackup::getNextFileToBackup(): failed to get next timetable file to back up!",
          e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void endIteration(boolean errorFree) {
    // timetableRecordMap is cleared in startIteration() already, so no need to clear it here
  }

  @Override
  protected BackupManager.BackupFile getNextFileToBackup() throws IOException {
    BackupManager.BackupFile nextFile = timetableBackupFile;
    timetableBackupFile = null;
    return nextFile;
  }

  @Override
  protected void backupComplete(BackupManager.BackupFile file) throws IOException {
    // TODO: consider updating BackupStatus if necessary
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
    FileOutputStream fos = new FileOutputStream(tempTimetableBackupFile);
    ObjectOutputStream oos = new ObjectOutputStream(fos);
    oos.writeObject(timetableRecordMap);
    oos.flush();
    oos.close();
    fos.close();
    return tempTimetableBackupFile;
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
