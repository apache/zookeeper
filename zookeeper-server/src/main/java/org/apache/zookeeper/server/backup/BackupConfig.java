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
import java.util.Optional;
import java.util.Properties;

import org.apache.zookeeper.common.ConfigException;
import org.apache.zookeeper.server.util.VerifyingFileFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backup-related configurations.
 */
public class BackupConfig {
  private static final Logger LOG = LoggerFactory.getLogger(BackupConfig.class);

  /*
   * Default constants
   */
  private static final boolean DEFAULT_BACKUP_ENABLED = false;
  public static final int DEFAULT_RETENTION_DAYS = 20;
  public static final long DEFAULT_BACKUP_INTERVAL_MS = 30 * 60 * 1000L; // 30 minutes
  /*
   * For retention maintenance, -1 indicates no maintenance by default.
   * Some storage backends support TTL natively.
   */
  public static final long DEFAULT_RETENTION_MAINTENANCE_INTERVAL_MS = -1L;

  private final File statusDir;
  private final File tmpDir;
  private final long backupIntervalInMs;
  private final int retentionPeriodInDays;
  private final long retentionMaintenanceIntervalInMs;
  /*
   * Fully-qualified Java class name for the storage implementation. See BackupStorageType.java
   * for example.
   */
  private final String storageProviderClassName;
  /*
   * Storage config file (optional).
   * E.g.) HDFS config
   */
  private final File storageConfig;
  /*
   * The custom path under which the backup files will be stored in.
   */
  private final String backupStoragePath;
  /*
   * The custom namespace string under which the backup files will be stored in.
   * E.g.) <backupStoragePath>/<namespace>/snapshot/snapshot-123456
   *       <backupStoragePath>/<namespace>/translog/translog-123456
   */
  private final String namespace;

  public BackupConfig(Builder builder) {
    this.statusDir = builder.statusDir.get();
    this.tmpDir = builder.tmpDir.get();
    this.backupIntervalInMs = builder.backupIntervalInMs.orElse(DEFAULT_BACKUP_INTERVAL_MS);
    this.retentionPeriodInDays = builder.retentionPeriodInDays.orElse(DEFAULT_RETENTION_DAYS);
    this.retentionMaintenanceIntervalInMs =
        builder.retentionMaintenanceIntervalInMs.orElse(DEFAULT_RETENTION_MAINTENANCE_INTERVAL_MS);
    this.storageProviderClassName = builder.storageProviderClassName.get();
    this.storageConfig = builder.storageConfig.orElse(null);
    this.backupStoragePath = builder.backupStoragePath.orElse("");
    this.namespace = builder.namespace.orElse("");
  }

  public File getStatusDir() {
    return statusDir;
  }

  public File getTmpDir() {
    return tmpDir;
  }

  public long getBackupInterval() {
    return backupIntervalInMs;
  }

  public int getRetentionPeriod() {
    return retentionPeriodInDays;
  }

  public long getRetentionMaintenanceInterval() {
    return retentionMaintenanceIntervalInMs;
  }

  public String getStorageProviderClassName() {
    return storageProviderClassName;
  }

  public File getStorageConfig() {
    return storageConfig;
  }

  public String getBackupStoragePath() {
    return backupStoragePath;
  }

  public String getNamespace() {
    return namespace;
  }

  public static class Builder {
    private static final VerifyingFileFactory vff =
        new VerifyingFileFactory.Builder(LOG).warnForRelativePath().build();

    private Optional<Boolean> enabled = Optional.empty();
    private Optional<File> statusDir = Optional.empty();
    private Optional<File> tmpDir = Optional.empty();
    private Optional<Long> backupIntervalInMs = Optional.of(DEFAULT_BACKUP_INTERVAL_MS);
    private Optional<Integer> retentionPeriodInDays = Optional.of(DEFAULT_RETENTION_DAYS);
    private Optional<Long> retentionMaintenanceIntervalInMs =
        Optional.of(DEFAULT_RETENTION_MAINTENANCE_INTERVAL_MS);
    private Optional<String> storageProviderClassName = Optional.empty();
    private Optional<File> storageConfig = Optional.empty();
    private Optional<String> backupStoragePath = Optional.empty();
    private Optional<String> namespace = Optional.empty();

    public Builder setEnabled(boolean enabled) {
      this.enabled = Optional.of(enabled);
      return this;
    }

    public Builder setStatusDir(File dir) {
      this.statusDir = Optional.of(dir);
      return this;
    }

    public Builder setTmpDir(File dir) {
      this.tmpDir = Optional.of(dir);
      return this;
    }

    public Builder setBackupInterval(long intervalInMs) {
      this.backupIntervalInMs = Optional.of(intervalInMs);
      return this;
    }

    public Builder setRetentionPeriodInDays(int retentionPeriodInDays) {
      this.retentionPeriodInDays = Optional.of(retentionPeriodInDays);
      return this;
    }

    public Builder setRetentionMaintenanceInterval(long retentionMaintenanceInterval) {
      this.retentionMaintenanceIntervalInMs = Optional.of(retentionMaintenanceInterval);
      return this;
    }

    public Builder setStorageProviderClassName(String storageProviderClassName) {
      this.storageProviderClassName = Optional.of(storageProviderClassName);
      return this;
    }

    public Builder setStorageConfig(File storageConfig) {
      this.storageConfig = Optional.of(storageConfig);
      return this;
    }

    public Builder setBackupStoragePath(String backupStoragePath) {
      this.backupStoragePath = Optional.of(backupStoragePath);
      return this;
    }

    public Builder setNamespace(String namespace) {
      this.namespace = Optional.of(namespace);
      return this;
    }

    public Builder withProperties(Properties properties) throws ConfigException {
      return withProperties(properties, "");
    }

    public Builder withProperties(Properties properties, String prefix) throws ConfigException {
      {
        String key = prefix + BackupSystemProperty.BACKUP_ENABLED;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.enabled = Optional.of(parseBoolean(key, prop));
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_STATUS_DIR;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.statusDir = Optional.of(vff.create(prop));
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_TMP_DIR;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.tmpDir = Optional.of(vff.create(prop));
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_INTERVAL_MS;
        String prop = properties.getProperty(key);
        if (prop != null) {
          long ms = parseLong(key, prop);
          this.backupIntervalInMs = Optional.of(ms);
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_STORAGE_CONFIG;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.storageConfig = Optional.of(vff.create(prop));
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_MOUNT_PATH;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.backupStoragePath = Optional.of(prop);
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_RETENTION_DAYS;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.retentionPeriodInDays = Optional.of(parseInt(key, prop));
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_RETENTION_MAINTENANCE_INTERVAL_MS;
        String prop = properties.getProperty(key);
        if (prop != null) {
          long ms = parseLong(key, prop);
          this.retentionMaintenanceIntervalInMs = Optional.of(ms);
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_STORAGE_PROVIDER_CLASS_NAME;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.storageProviderClassName = Optional.of(prop);
        }
      }
      {
        String key = prefix + BackupSystemProperty.BACKUP_NAMESPACE;
        String prop = properties.getProperty(key);
        if (prop != null) {
          this.namespace = Optional.of(prop);
        }
      }
      return this;
    }

    protected Builder withProperty(String key, String val) throws ConfigException {
      Properties properties = new Properties();
      properties.setProperty(key, val);
      return withProperties(properties);
    }

    private static long parseLong(String key, String value) throws ConfigException {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException exc) {
        throw new ConfigException(String.format("parsing %s", key), exc);
      }
    }

    private static int parseInt(String key, String value) throws ConfigException {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException exc) {
        throw new ConfigException(String.format("parsing %s", key), exc);
      }
    }

    private static boolean parseBoolean(String key, String value) {
      boolean result;
      switch (value.toLowerCase()) {
        case "yes":
          result = true;
          break;
        case "no":
          result = false;
          break;
        default:
          result = Boolean.parseBoolean(value);
          break;
      }
      return result;
    }

    public Optional<BackupConfig> build() throws ConfigException {
      final Optional<BackupConfig> result;
      if (enabled.orElse(DEFAULT_BACKUP_ENABLED)) {
        validate();
        result = Optional.of(new BackupConfig(this));
      } else {
        result = Optional.empty();
      }
      return result;
    }

    /**
     * Validates the backup config. Makes sure that required fields are present.
     * @throws ConfigException
     */
    private void validate() throws ConfigException {
      if (!statusDir.isPresent()) {
        throw new ConfigException("BackupConfig statusDir not specified");
      }
      if (!tmpDir.isPresent()) {
        throw new ConfigException("BackupConfig tmpDir not specified");
      }
      if (!storageProviderClassName.isPresent() || storageProviderClassName.get().equals("")) {
        throw new ConfigException("Please specify a valid storage provider class name.");
      }
    }
  }
}
