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
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.TreeMap;

import org.apache.zookeeper.server.backup.exception.BackupException;

/**
 * Util methods used to operate on timetable backup.
 */
public final class TimetableUtil {
  private static final String LATEST = "latest";
  private static final String TIMETABLE_PREFIX = "timetable.";

  private TimetableUtil() {
    // Util class
  }

  /**
   * Returns the last zxid corresponding to the timestamp preceding the timestamp given as argument.
   * Note: the given timestamp must be in the comprehensive range created from the timetable
   * backup files unless it is "latest". This is to prevent any human mistakes of accidentally
   * entering an arbitrarily high timestamp value and the tool restoring to the latest backup point.
   * @param timetableBackupFiles
   * @param timestamp timestamp string (long), or "latest"
   * @return Hex String representation of the zxid found
   */
  public static String findLastZxidFromTimestamp(File[] timetableBackupFiles, String timestamp) {
    // Verify argument: backup files
    if (timetableBackupFiles == null || timetableBackupFiles.length == 0) {
      throw new IllegalArgumentException(
          "TimetableUtil::findLastZxidFromTimestamp(): timetableBackupFiles argument is either null"
              + " or empty!");
    }

    // Verify argument: timestamp
    boolean isLatest = timestamp.equalsIgnoreCase(LATEST);
    long timestampLong;
    if (isLatest) {
      timestampLong = Long.MAX_VALUE;
    } else {
      try {
        timestampLong = Long.decode(timestamp);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "TimetableUtil::findLastZxidFromTimestamp(): cannot convert the given timestamp to a"
                + " valid long! timestamp: " + timestamp, e);
      }
    }

    // Traverse the files and find the lower bound, upper bound, and the file that contains the
    // timestamp
    long lowerBound = Long.MAX_VALUE, upperBound = Long.MIN_VALUE, lowestDelta = Long.MAX_VALUE;
    File fileToRead = null;
    for (File file : timetableBackupFiles) {
      String[] range = file.getName().replaceAll(TIMETABLE_PREFIX, "").split("-");
      long low = Long.parseLong(range[0]), high = Long.parseLong(range[1]);
      lowerBound = Math.min(low, lowerBound);
      upperBound = Math.max(high, upperBound);
      if (isLatest) {
        if (upperBound == high) {
          fileToRead = file;
        }
      } else {
        // Calculate the delta to find the closest available file either containing the timestamp
        // in its range or before the timestamp
        long delta = timestampLong - low;
        if (delta >= 0 && delta < lowestDelta) {
          lowestDelta = delta;
          fileToRead = file;
        }
      }
    }

    // Check if the given timestamp is in range
    if (!isLatest && (timestampLong < lowerBound || timestampLong > upperBound)) {
      throw new IllegalArgumentException(
          "TimetableUtil::findLastZxidFromTimestamp(): timestamp given is not in the timestamp "
              + "range given in the backup files!");
    }

    // Check if a file is found (this shouldn't happen if timestamp is in range)
    if (fileToRead == null) {
      throw new IllegalArgumentException(
          "TimetableUtil::findLastZxidFromTimestamp(): unable to find the backup file to use!");
    }

    // Convert timetable backup files to an ordered Map<Long, String>, timestamp:zxid pairs
    TreeMap<Long, String> timestampZxidPairs = new TreeMap<>();
    try {
      FileInputStream fis = new FileInputStream(fileToRead);
      ObjectInputStream ois = new ObjectInputStream(fis);
      @SuppressWarnings("unchecked")
      Map<Long, String> map = (TreeMap<Long, String>) ois.readObject();
      timestampZxidPairs.putAll(map);
    } catch (Exception e) {
      throw new BackupException(
          "TimetableUtil::findLastZxidFromTimestamp(): failed to read timetable backup files!", e);
    }

    // Find the last zxid corresponding to the timestamp given
    Map.Entry<Long, String> floorEntry = timestampZxidPairs.floorEntry(timestampLong);
    if (floorEntry == null) {
      throw new BackupException("TimetableUtil::findLastZxidFromTimestamp(): floorEntry is null!");
    }
    return floorEntry.getValue();
  }
}
