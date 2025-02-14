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

/**
 * This class implements ZK backup MBean
 */
public class BackupBean implements ZKMBeanInfo, BackupMXBean {
  private final BackupStats backupStats;
  private final String name;

  public BackupBean(BackupStats backupStats, long serverId) {
    this.backupStats = backupStats;
    this.name = "Backup_id" + serverId;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean isHidden() {
    return false;
  }

  // Snapshot backup metrics
  @Override
  public int getNumConsecutiveFailedSnapshotIterations() {
    return backupStats.getNumConsecutiveFailedSnapshotIterations();
  }

  @Override
  public long getMinutesSinceLastSuccessfulSnapshotIteration() {
    return backupStats.getMinutesSinceLastSuccessfulSnapshotIteration();
  }

  @Override
  public boolean getSnapshotBackupActiveStatus() {
    return backupStats.getSnapshotBackupActiveStatus();
  }

  @Override
  public long getLastSnapshotIterationDuration() {
    return backupStats.getSnapshotIterationDuration();
  }

  @Override
  public int getNumberOfSnapshotFilesBackedUpLastIteration() {
    return backupStats.getNumberOfSnapshotFilesBackedUpLastIteration();
  }

  // Transaction log backup metrics
  @Override
  public int getNumConsecutiveFailedTxnLogIterations() {
    return backupStats.getNumConsecutiveFailedTxnLogIterations();
  }

  @Override
  public long getMinutesSinceLastSuccessfulTxnLogIteration() {
    return backupStats.getMinutesSinceLastSuccessfulTxnLogIteration();
  }

  @Override
  public boolean getTxnLogBackupActiveStatus() {
    return backupStats.getTxnLogBackupActiveStatus();
  }

  @Override
  public long getLastTxnLogIterationDuration() {
    return backupStats.getTxnLogIterationDuration();
  }
}
