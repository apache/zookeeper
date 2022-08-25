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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinate the backup status among processes local to a server;
 * coordination across servers is better served via either an extension
 * to the ZAB protocol or by some external mechanism.
 */
public class BackupStatus {
  private static final Logger LOG = LoggerFactory.getLogger(BackupStatus.class);

  /**
   * The name for the backup status file.
   */
  public static final String STATUS_FILENAME = "zkBackupStatus";

  private static final String BACKEDUP_LOG_ZXID_TAG = "backedupLogZxid";
  private static final String BACKEDUP_SNAP_ZXID_TAG = "backedupSnapZxid";
  private static final String BACKEDUP_TIMETABLE_TIMESTAMP_TAG = "backedupTimetableTimestamp";
  private File statusFile;

  /**
   * Create an instance of BackupStatus for reading and updating the
   * status
   * @param backupStatusDir the directory for the backup status file
   * @throws IOException
   */
  public BackupStatus(File backupStatusDir) {
    statusFile = null;

    if (backupStatusDir != null) {
      statusFile = new File(backupStatusDir, STATUS_FILENAME);
    }
  }

  /**
   * Create the backup status if it does not already exist;  Initializes backup
   * point to 0L, -1L, -1L.  Otherwise returns the current backup point.
   * @return the current backup point
   * @throws IOException
   */
  public synchronized BackupPoint createIfNeeded() throws IOException {
    if (statusFile == null) {
      throw new IllegalArgumentException("A backup status directory has not been specified.");
    }

    if (!statusFile.getParentFile().exists()) {
      if (!statusFile.getParentFile().mkdirs()) {
        LOG.warn("Failed to create dirs");
      }
    }

    if (!statusFile.exists()) {
      update(new BackupPoint(BackupUtil.INVALID_LOG_ZXID, BackupUtil.INVALID_SNAP_ZXID,
          BackupUtil.INVALID_TIMESTAMP));
    }

    return read();
  }

  /**
   * Get the latest backup point
   * @return the latest backup point, or MAX_VALUE, MAX_VALUE, MAX_VALUE if the backup
   *      status file does not exist
   */
  public synchronized BackupPoint read() throws IOException {
    BackupPoint status = new BackupPoint(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

    if (statusFile != null && statusFile.exists()) {
      FileInputStream is = null;
      FileLock fileLock = null;

      // Synchronize with writers from other processes
      try {
        is = new FileInputStream(statusFile);
        InputArchive ia = BinaryInputArchive.getArchive(is);
        fileLock = is.getChannel().lock(0L, Long.MAX_VALUE, true);
        status =
            new BackupPoint(ia.readLong(BACKEDUP_LOG_ZXID_TAG), ia.readLong(BACKEDUP_SNAP_ZXID_TAG),
                ia.readLong(BACKEDUP_TIMETABLE_TIMESTAMP_TAG));
      } finally {
        if (fileLock != null) {
          fileLock.release();
        }

        if (is != null) {
          is.close();
        }
      }
    }

    return status;
  }

  /**
   * Update the backup point in the status file.  Creates the status file if needed.
   * @param backupPoint the new backup point
   * @throws IOException
   */
  public synchronized void update(BackupPoint backupPoint) throws IOException {
    if (statusFile == null) {
      throw new IllegalArgumentException("A backup status file has not been specified.");
    }

    if (!statusFile.exists()) {
      LOG.info("BackupStatus::update(): BackupStatus file doesn't exist. Creating file at path: "
          + statusFile.getAbsolutePath());
       if (!statusFile.createNewFile()) {
         LOG.warn("Failed to delete file: {}", statusFile);
       }
    }

    FileOutputStream os = null;
    FileLock fileLock = null;

    try {
      os = new FileOutputStream(statusFile, false);
      OutputArchive oa = BinaryOutputArchive.getArchive(os);
      // Synchronize with readers and writers from other processes
      fileLock = os.getChannel().lock();
      oa.writeLong(backupPoint.getLogZxid(), BACKEDUP_LOG_ZXID_TAG);
      oa.writeLong(backupPoint.getSnapZxid(), BACKEDUP_SNAP_ZXID_TAG);
      oa.writeLong(backupPoint.getTimestamp(), BACKEDUP_TIMETABLE_TIMESTAMP_TAG);
    } finally {
      if (os != null) {
        os.flush();
      }

      if (fileLock != null) {
        fileLock.release();
      }

      if (os != null) {
        os.close();
      }
    }
  }
}