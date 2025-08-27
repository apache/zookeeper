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
 * System property Strings for ZooKeeper backup.
 */
public class BackupSystemProperty {
  public static final String BACKUP_ENABLED = "backup.enabled";
  public static final String BACKUP_STATUS_DIR = "backup.statusDir";
  public static final String BACKUP_TMP_DIR = "backup.tmpDir";
  public static final String BACKUP_INTERVAL_MINUTES = "backup.intervalMinutes";
  public static final String BACKUP_RETENTION_DAYS = "backup.retentionDays";
  public static final String BACKUP_RETENTION_MAINTENANCE_INTERVAL_HOURS =
      "backup.retentionMaintenanceIntervalHours";
  public static final String BACKUP_STORAGE_PROVIDER_CLASS_NAME = "backup.storageProviderClassName";
  public static final String BACKUP_STORAGE_CONFIG = "backup.storageConfig";
  public static final String BACKUP_STORAGE_PATH = "backup.storagePath";
  public static final String BACKUP_NAMESPACE = "backup.namespace";

  // Backup timetable properties
  public static final String BACKUP_TIMETABLE_ENABLED = "backup.timetable.enabled";
  public static final String BACKUP_TIMETABLE_STORAGE_PATH = "backup.timetable.storagePath";
  public static final String BACKUP_TIMETABLE_BACKUP_INTERVAL_MS =
      "backup.timetable.backupIntervalInMs";
}
