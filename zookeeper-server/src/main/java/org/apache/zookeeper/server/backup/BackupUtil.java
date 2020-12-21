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
import java.util.*;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import org.apache.zookeeper.server.persistence.Util;

/**
 * Utility methods related to backups
 */
public class BackupUtil {
  // first valid tnxlog zxid is 1 while first valid zxid for snapshots is 0
  public static final long INVALID_LOG_ZXID = 0;
  public static final long INVALID_SNAP_ZXID = -1;
  public static final String LOST_LOG_PREFIX = "lostLogs";

  /**
   * Identifiers for the two zxids in a backedup file name
   */
  public enum ZxidPart {
    MIN_ZXID,
    MAX_ZXID
  }

  /**
   * Identifiers for the backup file types
   */
  public enum BackupFileType {
    SNAPSHOT(Util.SNAP_PREFIX),
    TXNLOG(Util.TXLOG_PREFIX),
    LOSTLOG(BackupUtil.LOST_LOG_PREFIX);

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
    private ZxidPart whichZxid;

    public BackupFileComparator(ZxidPart whichZxid, SortOrder sortOrder) {
      this.sortOrder = sortOrder;
      this.whichZxid = whichZxid;
    }

    public int compare(BackupFileInfo o1, BackupFileInfo o2) {
      long z1 = o1.getZxid(whichZxid);
      long z2 = o2.getZxid(whichZxid);

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
          return fileInfo.getZxidRange();
        }
      };

  public static String makeBackupName(String standardName, long highZxid) {
    return standardName.indexOf('-') >= 0
        ? standardName
        : String.format("%s-%x", standardName, highZxid);
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
   * Sort a list of backup files based on the specified zxid in the requested sort order
   * @param files the files to sort
   * @param whichZxid which zxid to sort by
   * @param sortOrder which direction in which to sort the zxid
   */
  public static void sort(List<BackupFileInfo> files, ZxidPart whichZxid, SortOrder sortOrder) {
    Collections.sort(files, new BackupFileComparator(whichZxid, sortOrder));
  }

  /**
   * Get an zxid range from the backup file name
   * @param name the name of the file
   * @param prefix the prefix to match
   * @return return the zxid range for the file
   */
  public static Range<Long> getZxidRangeFromName(String name, String prefix) {
    Range<Long> zxidRange = Range.singleton(-1L);
    String nameParts[] = name.split("[\\.-]");

    if (nameParts.length == 3 && nameParts[0].equals(prefix)) {
      try {
        zxidRange = Range.closed(Long.parseLong(nameParts[1], 16), Long.parseLong(nameParts[2], 16));
      } catch (NumberFormatException e) { }
    }

    return zxidRange;
  }

  /**
   * Sort backup files by the min zxid part in ascending order
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @return the files listed in the specified order using the request zxid
   */
  public static List<BackupFileInfo> getBackupFilesByMin(BackupStorageProvider bsp, BackupFileType fileType)
      throws IOException {
    return getBackupFilesByMin(bsp, null, fileType);
  }

  /**
   * Sort backup files by the min zxid part in ascending order
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @return the files listed in the specified order using the request zxid
   */
  public static List<BackupFileInfo> getBackupFilesByMin(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType) throws IOException {

    return getBackupFiles(bsp, path, fileType, ZxidPart.MIN_ZXID, SortOrder.ASCENDING);
  }

  /**
   * Sort backup files by the specified zxid part
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @param whichZxid which zxid to sort by
   * @param sortOrder which direction to sort the zxid in
   * @return the file info for the matching files sorted on the request zxid
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      BackupFileType fileType,
      ZxidPart whichZxid,
      SortOrder sortOrder) throws IOException {

    return getBackupFiles(bsp, null, fileType, whichZxid, sortOrder);
  }

  /**
   * Sort backup files by the specified zxid part
   * @param bsp the backup provide from which to get the files
   * @param fileType the backup file to get
   * @param whichZxid which zxid to sort by
   * @param sortOrder which direction to sort the zxid in
   * @return the file info for the matching files sorted on the request zxid
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType,
      ZxidPart whichZxid, SortOrder
      sortOrder) throws IOException {

    return getBackupFiles(bsp, path, new BackupFileType[] { fileType }, whichZxid, sortOrder);
  }

  /**
   * Sort the backup files of the requested types by the specified zxid part
   * @param bsp the backup provide from which to get the files
   * @param fileTypes the backup file types to get
   * @param whichZxid which zxid to sort by
   * @param sortOrder which direction in which to sort based on the requested zxid
   * @return the file info for the matching files sorted on the request zxid
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      BackupFileType[] fileTypes,
      ZxidPart whichZxid,
      SortOrder sortOrder) throws IOException {

    return getBackupFiles(bsp, null, fileTypes, whichZxid, sortOrder);
  }

  /**
   * Sort the backup files of the requested types by the specified zxid part
   * @param bsp the backup provide from which to get the files
   * @param fileTypes the backup file types to get
   * @param whichZxid which zxid to sort by
   * @param sortOrder which direction in which to sort based on the requested zxid
   * @return the file info for the matching files sorted on the request zxid
   */
  public static List<BackupFileInfo> getBackupFiles(
      BackupStorageProvider bsp,
      File path,
      BackupFileType[] fileTypes,
      ZxidPart whichZxid,
      SortOrder sortOrder) throws IOException {

    List<BackupFileInfo> files = new ArrayList<BackupFileInfo>();

    if (fileTypes.length == 1) {
      files = bsp.getBackupFileInfos(path, getPrefix(fileTypes[0]));
    } else {
      for (BackupFileType fileType : fileTypes) {
        files.addAll(bsp.getBackupFileInfos(path, getPrefix(fileType)));
      }
    }

    sort(files, whichZxid, sortOrder);
    return files;
  }

  /**
   * Get the latest backup file of the given type.
   * @param bsp the backup storage provider
   * @param fileType the file to get
   * @param whichZxid sorted based on which zxid
   * @return the latest backup file if one exists, null otherwise
   * @throws IOException
   */
  public static BackupFileInfo getLatest(
      BackupStorageProvider bsp,
      BackupFileType fileType,
      ZxidPart whichZxid) throws IOException {
    List<BackupFileInfo> files = getBackupFiles(bsp, fileType, whichZxid, SortOrder.DESCENDING);

    return files.isEmpty() ? null : files.get(0);
  }

  /**
   * Get both the zxids for each of the files matching the prefix
   * @param bsp the backup storage provide to get the files from
   * @param fileType the backup file to get
   * @return the list of zxid pairs
   * @throws IOException
   */
  public static List<Range<Long>> getZxids(BackupStorageProvider bsp, BackupFileType fileType)
      throws IOException {

    return getZxids(bsp, null, fileType);
  }

  /**
   * Get both the zxids for each of the files matching the prefix
   * @param bsp the backup storage provide to get the files from
   * @param fileType the backup file to get
   * @return the list of zxid pairs
   * @throws IOException
   */
  public static List<Range<Long>> getZxids(
      BackupStorageProvider bsp,
      File path,
      BackupFileType fileType) throws IOException {

    return Lists.newArrayList(
        Lists.transform(
            getBackupFiles(bsp, path, fileType, ZxidPart.MIN_ZXID, SortOrder.ASCENDING),
            zxidRangeExtractor));
  }

  // Utility class
  private BackupUtil() {}
}