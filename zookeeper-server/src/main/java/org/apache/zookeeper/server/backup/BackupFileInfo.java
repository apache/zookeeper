/**
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

import com.google.common.collect.Range;

import org.apache.zookeeper.server.backup.BackupUtil.BackupFileType;
import org.apache.zookeeper.server.backup.BackupUtil.IntervalEndpoint;
import org.apache.zookeeper.server.backup.timetable.TimetableBackup;
import org.apache.zookeeper.server.persistence.Util;

/**
 * Metadata for a file that has been backed-up
 * Assumes that the name of the backed up file uses the format:
 * prefix.lowzxid-highzxid where prefix is one of the standard snap or log file prefixes, or
 * "lostLog".
 * In the case of timetable backup file, it takes a format of timetable.lowTimestamp-highTimestamp.
 */
public class BackupFileInfo {
  private final File backupFile;
  private final File standardFile;
  private final Range<Long> range;
  private final BackupFileType fileType;
  private final long modificationTime;
  private final long size;
  public static final long NOT_SET = -1;

  /**
   * Constructor that pulls backup metadata based on the backed-up filename
   * @param backedupFile the backed-up file with the name in the form prefix.lowzxid-highzxid
   *                     for example snapshot.9a0000a344-9a0000b012.
   *                     if timetable backup, ranges are decimal longs (posix timestamps)
   * @param modificationTime the file modification time
   * @param size the size of the file in bytes
   */
  public BackupFileInfo(File backedupFile, long modificationTime, long size) {
    this.backupFile = backedupFile;
    this.modificationTime = modificationTime;
    this.size = size;

    String backedupFilename = this.backupFile.getName();

    if (backedupFilename.startsWith(BackupUtil.LOST_LOG_PREFIX)) {
      this.fileType = BackupFileType.LOSTLOG;
      this.standardFile = this.backupFile;
    } else if (backedupFilename.startsWith(Util.SNAP_PREFIX)) {
      this.fileType = BackupFileType.SNAPSHOT;
      this.standardFile =
          new File(this.backupFile.getParentFile(), backedupFilename.split("-")[0]);
    } else if (backedupFilename.startsWith(Util.TXLOG_PREFIX)) {
      this.fileType = BackupFileType.TXNLOG;
      this.standardFile =
          new File(this.backupFile.getParentFile(), backedupFilename.split("-")[0]);
    } else if (backedupFilename.startsWith(TimetableBackup.TIMETABLE_PREFIX)) {
      this.fileType = BackupFileType.TIMETABLE;
      this.standardFile = this.backupFile;
    } else {
      throw new IllegalArgumentException("Not a known backup file type: " + backedupFilename);
    }

    this.range = BackupUtil.getRangeFromName(
        backedupFilename,
        BackupUtil.getPrefix(this.fileType));
  }

  /**
   * Get the zxid range for the backed up file.
   * @return the zxid range
   */
  public Range<Long> getRange() {
    return this.range;
  }

  /**
   * Convenience method for getting a specific interval endpoint (e.g. zxid, timestamp, etc.)
   * @param whichIntervalEndpoint which range part to get
   * @return the value of the requested range endpoint
   */
  public long getIntervalEndpoint(IntervalEndpoint whichIntervalEndpoint) {
    return whichIntervalEndpoint == IntervalEndpoint.START
        ? this.range.lowerEndpoint()
        : this.range.upperEndpoint();
  }

  /**
   * Get the backedup file
   * @return the backedup file
   */
  public File getBackedUpFile() {
    return this.backupFile;
  }

  /**
   * Get the files corresponding to the standard name of the backed up file.  I.e. removes the
   * high zxid from the filename.
   * @return the standard file corresponding to the backed up file
   */
  public File getStandardFile() {
    return this.standardFile;
  }

  /**
   * Get the type of the file (snap, log, lostLog)
   * @return the type of file that was backed up
   */
  public BackupFileType getFileType() {
    return this.fileType;
  }

  /**
   * Get the modification time for the file
   * @return the modification time
   */
  public long getModificationTime() {
    return this.modificationTime;
  }

  /**
   * Get the file size in bytes
   * @return file size in bytes
   */
  public long getSize() {
    return this.size;
  }
}