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

/**
 * Backup-related configurations
 */
public class BackupConfig {
  public static final String NFS_BACKUP_STORAGE = "NFSBackupStorage";
  private final String storageProviderClassName;
  private final String mountPath;
  private final String namespace;
  private final int retentionPeriodInDays;

  private BackupConfig(String storageProviderClassName, String mountPath, String namespace,
      int retentionPeriodInDays) {
    this.storageProviderClassName = storageProviderClassName;
    this.mountPath = mountPath;
    this.namespace = namespace;
    this.retentionPeriodInDays = retentionPeriodInDays;
  }

  public String getStorageProviderClassName() {
    return storageProviderClassName;
  }

  public String getMountPath() {
    return mountPath;
  }

  public String getNamespace() {
    return namespace;
  }

  public int getRetentionPeriodInDays() {
    return retentionPeriodInDays;
  }

  public static class Builder {
    private String _storageProviderClassName;
    private String _mountPath;
    private String _namespace;
    private int _retentionPeriodInDays = 20;

    public Builder setStorageProviderClassName(String storageProviderClassName) {
      _storageProviderClassName = storageProviderClassName;
      return this;
    }

    public Builder setMountPath(String mountPath) {
      _mountPath = mountPath;
      return this;
    }

    public Builder setNamespace(String namespace) {
      this._namespace = namespace;
      return this;
    }

    public Builder setRetentionPeriodInDays(int retentionPeriodInDays) {
      this._retentionPeriodInDays = retentionPeriodInDays;
      return this;
    }

    public BackupConfig build() {
      validate();
      return new BackupConfig(_storageProviderClassName, _mountPath, _namespace,
          _retentionPeriodInDays);
    }

    private void validate() {
      if (_storageProviderClassName == null) {
        throw new IllegalArgumentException(
            "Please specify a storage provider as the backup storage.");
      }
      if (_namespace == null) {
        throw new IllegalArgumentException("Please specify a namespace for this host.");
      }
      if (_storageProviderClassName.equals(NFS_BACKUP_STORAGE) && _mountPath == null) {
        throw new IllegalArgumentException("Please specify a mount path for NFS client.");
      }
    }
  }
}
