package org.apache.zookeeper.backup;

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

import com.google.common.collect.Range;
import org.apache.zookeeper.server.persistence.Util;

public class BackupUtil {
  public static final String LOST_LOG_PREFIX = "lostLogs";

  /**
   * Identifiers for the two zxids in a backedup file name
   */
  public enum ZxidPart {
    MIN_ZXID, MAX_ZXID
  }

  /**
   * Identifiers for the backup file types
   */
  public enum BackupFileType {
    SNAPSHOT(Util.SNAP_PREFIX), TXNLOG(Util.TXLOG_PREFIX), LOSTLOG(BackupUtil.LOST_LOG_PREFIX);

    private final String prefix;

    BackupFileType(String prefix) {
      this.prefix = prefix;
    }

    public String getPrefix() {
      return prefix;
    }

    public static String toPrefix(BackupFileType fileType) {
      return fileType.getPrefix();
    }
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
        zxidRange =
            Range.closed(Long.parseLong(nameParts[1], 16), Long.parseLong(nameParts[2], 16));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Cannot parse zxid range from file name: " + name);
      }
    }

    return zxidRange;
  }
}
