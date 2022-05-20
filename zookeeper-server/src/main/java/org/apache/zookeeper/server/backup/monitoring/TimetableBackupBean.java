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

package org.apache.zookeeper.server.backup.monitoring;

import org.apache.zookeeper.jmx.ZKMBeanInfo;

public class TimetableBackupBean implements ZKMBeanInfo, TimetableBackupMXBean {
  public static final String TIMETABLE_BACKUP_MBEAN_NAME = "TimetableBackup";
  private final TimetableBackupStats backupStats;

  public TimetableBackupBean(TimetableBackupStats backupStats) {
    this.backupStats = backupStats;
  }

  @Override
  public String getName() {
    return TIMETABLE_BACKUP_MBEAN_NAME;
  }

  @Override
  public boolean isHidden() {
    return false;
  }

  // Timetable backup metrics
  @Override
  public int getNumConsecutiveFailedTimetableIterations() {
    return backupStats.getNumConsecutiveFailedTimetableIterations();
  }

  @Override
  public long getMinutesSinceLastSuccessfulTimetableIteration() {
    return backupStats.getMinutesSinceLastSuccessfulTimetableIteration();
  }

  @Override
  public boolean getTimetableBackupActiveStatus() {
    return backupStats.getTimetableBackupActiveStatus();
  }

  @Override
  public long getLastTimetableIterationDuration() {
    return backupStats.getTimetableIterationDuration();
  }
}
