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

/**
 * Describes the point to which backups of log and snap files have been completed. It also includes
 * a timestamp field to describe the last timestamp timetable has been backed up to (only
 * applicable when timetable is enabled).
 */
public class BackupPoint {
  private long logZxid;
  private long snapZxid;
  private long timestamp; // backup timetable is optional

  /**
   * Create an instance of a BackupPoint
   * @param logZxid the highest log zxid that has been backed up
   * @param snapZxid the start zxid of the latest snap that has been backedup
   * @param timestamp the latest timestamp that was backed up into timetable
   */
  public BackupPoint(long logZxid, long snapZxid, long timestamp) {
    this.logZxid = logZxid;
    this.snapZxid = snapZxid;
    this.timestamp = timestamp;
  }

  /**
   * Get the zxid up to which the log has been backed up.
   * @return the highest zxid that has been backed up
   */
  public long getLogZxid() {
    return logZxid;
  }

  public void setLogZxid(long logZxid) {
    this.logZxid = logZxid;
  }

  /**
   * Get the starting zxid of the latest backed up snap
   * @return the starting zxid of the latest backed up snap
   */
  public long getSnapZxid() {
    return snapZxid;
  }

  public void setSnapZxid(long snapZxid) {
    this.snapZxid = snapZxid;
  }

  /**
   * Get the starting timestamp of the latest backed up timetable backup file
   * @return the starting timestamp of the latest backed up timetable backup file
   */
  public long getTimestamp() {
    return timestamp;
  }

  /**
   * Set the ending timestamp of the latest backed up timetable backup file
   * @param timestamp
   * @return
   */
  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }
}