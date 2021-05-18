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
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import org.apache.zookeeper.server.backup.exception.BackupException;
import org.apache.zookeeper.server.backup.storage.BackupStorageProvider;
import org.apache.zookeeper.server.backup.timetable.TimetableBackup;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;

/**
 * Utility methods related to backups
 */
public class BackupUtil {
  // first valid tnxlog zxid is 1 while first valid zxid for snapshots is 0
  public static final long INVALID_LOG_ZXID = 0;
  public static final long INVALID_SNAP_ZXID = -1;
  // invalid timestamp is -1 by convention
  public static final long INVALID_TIMESTAMP = -1L;
  public static final String LOST_LOG_PREFIX = "lostLogs";
  public static final String LATEST = "latest";

  /**
   * Identifiers for the two zxids in a backedup file name
   */
  public enum IntervalEndpoint {
    START,
    END
  }

  /**
   * Identifiers for the backup file types
   */
  public enum BackupFileType {
    SNAPSHOT(Util.SNAP_PREFIX),
    TXNLOG(Util.TXLOG_PREFIX),
    LOSTLOG(BackupUtil.LOST_LOG_PREFIX),
    TIMETABLE(TimetableBackup.TIMETABLE_PREFIX);

    private final String prefix;

    BackupFileType(String prefix) {
      this.prefix = prefix;
    }

    public String getPrefix() {
      return prefix;
    }

    public static BackupFileType fromPrefix(String prefix) {
      switch (prefix) {
        case BackupUtil.LOST_LOG_PREFIX:
          return LOSTLOG;
        default:
          return fromBaseFileType(Util.FileType.fromPrefix(prefix));
      }
    }

    public static BackupFileType fromBaseFileType(Util.FileType type) {
      switch (type) {
        case SNAPSHOT:
          return BackupFileType.SNAPSHOT;
        case TXNLOG:
          return BackupFileType.TXNLOG;
        default:
          throw new IllegalArgumentException("Unknown base file type: " + type);
      }
    }

    public static Util.FileType toBaseFileType(BackupFileType type) {
      switch (type) {
        case SNAPSHOT:
          return Util.FileType.SNAPSHOT;
        case TXNLOG:
          return Util.FileType.TXNLOG;
        default:
          throw new IllegalArgumentException("No matching base file type for: " + type);
      }
    }
  }

  /**
   * Identifiers for sort direction
   */
  public enum SortOrder {
    ASCENDING,
    DESCENDING
  }

  private static class BackupFileComparator implements Comparator<BackupFileInfo>, Serializable
  {
    private static final long serialVersionUID = -2648639884525140318L;

    private SortOrder sortOrder;
    private IntervalEndpoint whichIntervalEndpoint;

    public BackupFileComparator(IntervalEndpoint whichIntervalEndpoint, SortOrder sortOrder) {
      this.sortOrder = sortOrder;
      this.whichIntervalEndpoint = whichIntervalEndpoint;
    }

    public int compare(BackupFileInfo o1, BackupFileInfo o2) {
      long z1 = o1.getIntervalEndpoint(whichIntervalEndpoint);
      long z2 = o2.getIntervalEndpoint(whichIntervalEndpoint);

      int result = Long.compare(z1, z2);

      if (result == 0) {
        File f1 = o1.getBackedUpFile();
        File f2 = o2.getBackedUpFile();

        result = f1.compareTo(f2);
      }

      return sortOrder == SortOrder.ASCENDING ? result : -result;
    }
  }

  private static Function<BackupFileInfo, Range<Long>> zxidRangeExtractor =
      new Function<BackupFileInfo, Range<Long>>() {
        public Range<Long> apply(BackupFileInfo fileInfo) {
          return fileInfo.getRange();
        }
      };

  /**
   * Creates a file name string for a backup file by appending a high zxid/long in Hex if it
   * doesn't already contain an ending zxid.
   * It also adds the snapshot filename StreamMode extension in case snapshot compression is
   * enabled. E.g.) snapshot.123.snappy -> snapshot.123-456.snappy
   * @param standardName
   * @param highZxid
   * @return
   */
  public static String makeBackupName(String standardName, long highZxid) {
    if (standardName.indexOf('-') >= 0) {
      // standard name already contains the zxid interval endpoint and if snapshot compression
      // is enabled, the standardName should already contain StreamMode extension, so just return
      return standardName;
    }
    if (SnapStream.getStreamMode(standardName).getName().isEmpty()) {
      // No snapshot compression is used for this snapshot, so just append the zxid interval
      // endpoint
      return String.format("%s-%x", standardName, highZxid);
    } else {
      // Snapshot compression is enabled; standardName looks like "snapshot.<zxid>.<streamMode>"
      // Need to append the ending zxid
      String[] nameParts = standardName.split("\\.");
      if (nameParts.length != 3) {
        throw new BackupException(
            "BackupUtil::makeBackupName(): unable to make backup name! standardName: "
                + standardName + " StreamMode: " + SnapStream.getStreamMode(standardName)
                .getName());
      }
      String zxidPart = String.format("%s-%x", nameParts[1], highZxid);
      // Combine all parts to generate a backup name
      return nameParts[0] + "." + zxidPart + "." + nameParts[2];
    }
  }

  /**
   * Helper method for getting the proper file prefix for a backup file type.
   * @param fileType the file type whose prefix to get
   * @return the prefix for the file
   */
  public static String getPrefix(BackupFileType fileType) {
    return fileType.getPrefix();
  }

  /**
   * Sort a list of backup files based on the specified interval endpoint in the requested
   * sort order
   * @param files the files to sort
   * @param whichIntervalEndpoint which interval endpoint to sort by
   * @param sortOrder which direction in which to sort the files
   */
  public static void sort(List<BackupFileInfo> files, IntervalEndpoint whichIntervalEndpoint, SortOrder sortOrder) {
    Collections.sort(files, new BackupFileComparator(whichIntervalEndpoint, sortOrder));
  }

  /**
   * Get a range from the backup file name. This range is usually a zxid range, but in the case of
   * timetable backup, it is a timestamp range.
   * @param name the name of the file
   * @param prefix the prefix to match
   * @return return the range for the file
   */
  public static Range<Long> getRangeFromName(String name, String prefix) {
    Range<Long> range = Range.singleton(-1L);
    String nameParts[] = name.split("[\\.-]");

    if (nameParts.length == 3 && nameParts[0].equals(prefix)) {
      try {
        // timetable backup files contain decimal longs, not hex
        int radix = prefix.equals(TimetableBackup.TIMETABLE_PREFIX) ? 10 : 16;
        range =
            Range.closed(Long.parseLong(nameParts[1], radix), Long.parseLong(nameParts[2], radix));
      } catch (NumberFormatException e) {
      }
    }

    return range;
  }

  /**
   * Sort backup files by the starting point of the interval (min zxid part) in ascending order
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @return the files listed in the specified order using the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFilesByMin(BackupStorageProvider bsp, BackupFileType fileType)
      throws IOException {
    return getBackupFilesByMin(bsp, null, fileType);
  }

  /**
   * Sort backup files by the starting point of the interval (min zxid part) in ascending order
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @return the files listed in the specified order using the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFilesByMin(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType) throws IOException {

    return getBackupFiles(bsp, path, fileType, IntervalEndpoint.START, SortOrder.ASCENDING);
  }

  /**
   * Sort backup files by the specified interval endpoint
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @param whichIntervalEndpoint which interval part (min or max) to sort by
   * @param sortOrder which direction to sort the values (e.g. zxid) in
   * @return the file info for the matching files sorted by the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      BackupFileType fileType,
      IntervalEndpoint whichIntervalEndpoint,
      SortOrder sortOrder) throws IOException {

    return getBackupFiles(bsp, null, fileType, whichIntervalEndpoint, sortOrder);
  }

  /**
   * Sort backup files by the specified interval endpoint
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @param whichIntervalEndpoint which interval endpoint to sort by
   * @param sortOrder which direction to sort the ranges in
   * @return the file info for the matching files sorted by the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType,
      IntervalEndpoint whichIntervalEndpoint, SortOrder
      sortOrder) throws IOException {

    return getBackupFiles(bsp, path, new BackupFileType[] { fileType }, whichIntervalEndpoint, sortOrder);
  }

  /**
   * Sort the backup files of the requested types by the specified interval endpoint
   * @param bsp the backup provide from which to get the files
   * @param fileTypes the backup file types to get
   * @param whichIntervalEndpoint which interval endpoint to sort by
   * @param sortOrder which direction in which to sort based on the requested interval endpoint
   * @return the file info for the matching files sorted by the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      BackupFileType[] fileTypes,
      IntervalEndpoint whichIntervalEndpoint,
      SortOrder sortOrder) throws IOException {

    return getBackupFiles(bsp, null, fileTypes, whichIntervalEndpoint, sortOrder);
  }

  /**
   * Sort the backup files of the requested types by the specified interval endpoint
   * @param bsp the backup provide from which to get the files
   * @param fileTypes the backup file types to get
   * @param whichIntervalEndpoint which interval endpoint to sort by
   * @param sortOrder which direction in which to sort based on the requested interval endpoint
   * @return the file info for the matching files sorted by the requested interval endpoint
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      File path,
      BackupFileType[] fileTypes,
      IntervalEndpoint whichIntervalEndpoint,
      SortOrder sortOrder) throws IOException {

    List<BackupFileInfo> files = new ArrayList<BackupFileInfo>();

    if (fileTypes.length == 1) {
      files = bsp.getBackupFileInfos(path, getPrefix(fileTypes[0]));
    } else {
      for (BackupFileType fileType : fileTypes) {
        files.addAll(bsp.getBackupFileInfos(path, getPrefix(fileType)));
      }
    }

    sort(files, whichIntervalEndpoint, sortOrder);
    return files;
  }

  /**
   * Get the latest backup file of the given type.
   * @param bsp the backup storage provider
   * @param fileType the file to get
   * @param whichIntervalEndpoint sorted based on which interval endpoint
   * @return the latest backup file if one exists, null otherwise
   * @throws IOException
   */
  public static BackupFileInfo getLatest(
      BackupStorageProvider bsp,
      BackupFileType fileType,
      IntervalEndpoint whichIntervalEndpoint) throws IOException {
    List<BackupFileInfo> files = getBackupFiles(bsp, fileType, whichIntervalEndpoint, SortOrder.DESCENDING);

    return files.isEmpty() ? null : files.get(0);
  }

  /**
   * Get both interval endpoints (Range) for each of the files matching the prefix
   * @param bsp the backup storage provide to get the files from
   * @param fileType the backup file to get
   * @return the list of interval endpoint pairs (ranges)
   * @throws IOException
   */
  public static List<Range<Long>> getRanges(BackupStorageProvider bsp, BackupFileType fileType)
      throws IOException {

    return getRanges(bsp, null, fileType);
  }

  /**
   * Get the ranges for each of the files matching the prefix
   * @param bsp the backup storage provide to get the files from
   * @param fileType the backup file to get
   * @return the list of zxid pairs
   * @throws IOException
   */
  public static List<Range<Long>> getRanges(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType) throws IOException {

    return Lists.newArrayList(
        Lists.transform(
            getBackupFiles(bsp, path, fileType, IntervalEndpoint.START, SortOrder.ASCENDING),
            zxidRangeExtractor));
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
  public static BackupStorageProvider createStorageProviderImpl(BackupConfig backupConfig)
      throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
             InvocationTargetException, InstantiationException {
    Class<?> clazz = Class.forName(backupConfig.getStorageProviderClassName());
    Constructor<?> constructor = clazz.getConstructor(BackupConfig.class);
    Object storageProvider = constructor.newInstance(backupConfig);
    return (BackupStorageProvider) storageProvider;
  }

  // Utility class
  private BackupUtil() {}
}